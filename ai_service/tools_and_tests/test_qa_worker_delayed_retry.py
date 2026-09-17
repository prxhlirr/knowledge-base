import json
import sys
import time
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"
sys.path.insert(0, str(AI_SERVICE))


class FakeRedis:
    """
    业务功能：为 QA 延迟重试函数提供最小 Redis 行为替身。
    关键流程：仅实现 zadd/zrangebyscore/zrem/lpush，避免单元测试依赖真实 Redis。
    设计原因：队列调度语义应可快速验证，不应加载 RAGPipeline 或连接外部服务。
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


def test_standalone_qa_retry_payload_waits_until_due():
    from task_worker_qa import QUEUE_QA, QUEUE_QARETRY, drain_due_qa_retry_payloads, enqueue_qa_retry_payload

    redis = FakeRedis()
    payload = {"taskId": "qa-delay-1", "retryCount": 1}
    retry_at = enqueue_qa_retry_payload(redis, payload, delay_seconds=60)

    assert retry_at > time.time()
    assert drain_due_qa_retry_payloads(redis, now=retry_at - 1) == 0
    assert redis.lists.get(QUEUE_QA) is None

    assert drain_due_qa_retry_payloads(redis, now=retry_at + 1) == 1
    assert json.loads(redis.lists[QUEUE_QA][0])["taskId"] == payload["taskId"]
    assert redis.zsets[QUEUE_QARETRY] == {}


def test_embedded_qa_retry_payload_waits_until_due_without_importing_runtime():
    from task_worker import QUEUE_QA, QUEUE_QA_RETRY, drain_due_qa_retry_payloads, enqueue_qa_retry_payload

    redis = FakeRedis()
    payload = {"taskId": "qa-delay-2", "retryCount": 1}
    retry_at = enqueue_qa_retry_payload(redis, payload, delay_seconds=30)

    assert drain_due_qa_retry_payloads(redis, now=retry_at - 1) == 0
    assert redis.lists.get(QUEUE_QA) is None

    assert drain_due_qa_retry_payloads(redis, now=retry_at + 1) == 1
    assert json.loads(redis.lists[QUEUE_QA][0])["taskId"] == payload["taskId"]
    assert redis.zsets[QUEUE_QA_RETRY] == {}


def test_qa_workers_default_missing_acl_to_no_access():
    from task_worker import DEFAULT_QA_ACL_TOKENS as embedded_default
    from task_worker_qa import DEFAULT_QA_ACL_TOKENS as standalone_default

    assert embedded_default == ["_NO_ACCESS"]
    assert standalone_default == ["_NO_ACCESS"]


if __name__ == "__main__":
    test_standalone_qa_retry_payload_waits_until_due()
    test_embedded_qa_retry_payload_waits_until_due_without_importing_runtime()
    test_qa_workers_default_missing_acl_to_no_access()
    print("PASS qa worker delayed retry tests")
