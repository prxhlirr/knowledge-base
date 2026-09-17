import json
import sys
import time
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"
sys.path.insert(0, str(AI_SERVICE))


class FakeRedis:
    """
    业务功能：为辅助索引补偿队列提供最小 Redis 替身。
    关键流程：只实现 sorted set 的 zadd/zrangebyscore/zrem，覆盖成功消费和失败重排。
    设计原因：补偿调度语义应可单测验证，不依赖真实 Redis 或 Elasticsearch。
    """

    def __init__(self):
        self.zsets = {}
        self.lists = {}

    def zadd(self, key, mapping):
        self.zsets.setdefault(key, {}).update(mapping)

    def zrangebyscore(self, key, min_score, max_score, start=0, num=None):
        items = [
            item
            for item, score in self.zsets.get(key, {}).items()
            if min_score <= score <= max_score
        ]
        items = items[start:]
        return items if num is None else items[:num]

    def zrem(self, key, item):
        if item in self.zsets.get(key, {}):
            del self.zsets[key][item]
            return 1
        return 0

    def lpush(self, key, item):
        self.lists.setdefault(key, []).insert(0, item)


class FakePipeline:
    def __init__(self):
        self.es = object()


class FakeStats:
    written = 2
    skipped_unresolved = 0


def test_auxiliary_index_retry_drains_successfully():
    import scripts.backfill_auxiliary_permission_projection as backfill_mod
    from task_worker import AUXILIARY_INDEX_RETRY_QUEUE, drain_due_auxiliary_index_retries

    redis = FakeRedis()
    retry_at = time.time() - 1
    redis.zadd(AUXILIARY_INDEX_RETRY_QUEUE, {
        json.dumps({"sourceName": "demo.docx", "retryAt": retry_at}, ensure_ascii=False): retry_at
    })

    calls = []
    original = backfill_mod.backfill_source
    backfill_mod.backfill_source = lambda **kwargs: calls.append(kwargs) or FakeStats()
    try:
        repaired = drain_due_auxiliary_index_retries(redis, FakePipeline(), now=time.time())
    finally:
        backfill_mod.backfill_source = original

    assert repaired == 2
    assert calls[0]["source_name"] == "demo.docx"
    assert redis.zsets[AUXILIARY_INDEX_RETRY_QUEUE] == {}


def test_auxiliary_index_retry_requeues_failed_repair():
    import scripts.backfill_auxiliary_permission_projection as backfill_mod
    from task_worker import AUXILIARY_INDEX_RETRY_QUEUE, drain_due_auxiliary_index_retries

    redis = FakeRedis()
    retry_at = time.time() - 1
    redis.zadd(AUXILIARY_INDEX_RETRY_QUEUE, {
        json.dumps({"sourceName": "broken.docx", "retryAt": retry_at}, ensure_ascii=False): retry_at
    })

    original = backfill_mod.backfill_source
    backfill_mod.backfill_source = lambda **kwargs: (_ for _ in ()).throw(RuntimeError("ES down"))
    try:
        repaired = drain_due_auxiliary_index_retries(redis, FakePipeline(), now=time.time())
    finally:
        backfill_mod.backfill_source = original

    assert repaired == 0
    queued = next(iter(redis.zsets[AUXILIARY_INDEX_RETRY_QUEUE].keys()))
    payload = json.loads(queued)
    assert payload["sourceName"] == "broken.docx"
    assert payload["attempt"] == 1
    assert "ES down" in payload["lastError"]


def test_auxiliary_index_retry_moves_to_dlq_after_max_attempts():
    import scripts.backfill_auxiliary_permission_projection as backfill_mod
    from task_worker import (
        AUXILIARY_INDEX_RETRY_MAX_ATTEMPTS,
        AUXILIARY_INDEX_RETRY_QUEUE,
        drain_due_auxiliary_index_retries,
    )

    redis = FakeRedis()
    retry_at = time.time() - 1
    redis.zadd(AUXILIARY_INDEX_RETRY_QUEUE, {
        json.dumps({
            "sourceName": "dead.docx",
            "attempt": AUXILIARY_INDEX_RETRY_MAX_ATTEMPTS - 1,
            "retryAt": retry_at,
        }, ensure_ascii=False): retry_at
    })

    original = backfill_mod.backfill_source
    backfill_mod.backfill_source = lambda **kwargs: (_ for _ in ()).throw(RuntimeError("mapping rejected"))
    try:
        repaired = drain_due_auxiliary_index_retries(redis, FakePipeline(), now=time.time())
    finally:
        backfill_mod.backfill_source = original

    assert repaired == 0
    assert redis.zsets[AUXILIARY_INDEX_RETRY_QUEUE] == {}
    dlq = redis.lists[f"{AUXILIARY_INDEX_RETRY_QUEUE}_DLQ"]
    payload = json.loads(dlq[0])
    assert payload["sourceName"] == "dead.docx"
    assert payload["attempt"] == AUXILIARY_INDEX_RETRY_MAX_ATTEMPTS
    assert "max attempts reached" in payload["lastError"]


if __name__ == "__main__":
    test_auxiliary_index_retry_drains_successfully()
    test_auxiliary_index_retry_requeues_failed_repair()
    test_auxiliary_index_retry_moves_to_dlq_after_max_attempts()
    print("PASS auxiliary index retry tests")
