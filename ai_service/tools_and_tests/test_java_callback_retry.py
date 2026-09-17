import json
import sys
import types
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"
sys.path.insert(0, str(AI_SERVICE))


class FakeRedis:
    """
    业务功能：为 Java 回调补偿逻辑提供最小 Redis 替身。
    关键流程：实现 sorted set 的 zadd/zrangebyscore/zrem，以及 DLQ 使用的 lpush。
    设计原因：权限闭环补偿只需要验证队列语义，不应依赖真实 Redis 或 Java 服务。
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


class FakeResponse:
    """
    业务功能：模拟 requests.post 返回对象。
    关键流程：仅提供 status_code/text 两个补偿逻辑依赖字段。
    设计原因：测试关注回调补偿调度，不关注 HTTP 客户端实现细节。
    """

    def __init__(self, status_code=200, text="ok"):
        self.status_code = status_code
        self.text = text


def install_fake_requests(status_code=200):
    fake_requests = types.SimpleNamespace(
        post=lambda *args, **kwargs: FakeResponse(status_code=status_code, text="mock")
    )
    sys.modules["requests"] = fake_requests


def test_java_callback_retry_drains_successfully():
    from core.rag_pipeline import (
        JAVA_CALLBACK_RETRY_QUEUE,
        drain_due_java_callback_retries,
        enqueue_java_callback_retry,
    )

    install_fake_requests(status_code=200)
    redis = FakeRedis()
    retry_at = enqueue_java_callback_retry(
        redis,
        "DOC_REGISTRY",
        "http://java/api/v1/internal/doc/registry",
        {"sourceName": "a.docx"},
        headers={"X-Internal-Token": "t"},
        delay_seconds=0,
    )

    assert drain_due_java_callback_retries(redis, now=retry_at + 1) == 1
    assert redis.zsets[JAVA_CALLBACK_RETRY_QUEUE] == {}


def test_java_callback_retry_requeues_failed_attempt():
    from core.rag_pipeline import (
        JAVA_CALLBACK_RETRY_QUEUE,
        drain_due_java_callback_retries,
        enqueue_java_callback_retry,
    )

    install_fake_requests(status_code=503)
    redis = FakeRedis()
    retry_at = enqueue_java_callback_retry(
        redis,
        "PERMISSION_EVENT",
        "http://java/api/doc/perm/record",
        {"sourceName": "b.docx"},
        delay_seconds=0,
    )

    assert drain_due_java_callback_retries(redis, now=retry_at + 1) == 0
    queued = next(iter(redis.zsets[JAVA_CALLBACK_RETRY_QUEUE].keys()))
    assert json.loads(queued)["attempt"] == 1


def test_java_callback_retry_moves_to_dlq_after_max_attempts():
    import core.rag_pipeline as rag_pipeline
    from core.rag_pipeline import (
        JAVA_CALLBACK_RETRY_QUEUE,
        drain_due_java_callback_retries,
        enqueue_java_callback_retry,
    )

    install_fake_requests(status_code=500)
    redis = FakeRedis()
    retry_at = enqueue_java_callback_retry(
        redis,
        "DOC_REGISTRY",
        "http://java/api/v1/internal/doc/registry",
        {"sourceName": "c.docx"},
        attempt=rag_pipeline.JAVA_CALLBACK_RETRY_MAX_ATTEMPTS - 1,
        delay_seconds=0,
    )

    assert drain_due_java_callback_retries(redis, now=retry_at + 1) == 0
    assert redis.zsets[JAVA_CALLBACK_RETRY_QUEUE] == {}
    dlq = redis.lists[f"{JAVA_CALLBACK_RETRY_QUEUE}_DLQ"]
    assert json.loads(dlq[0])["payload"]["sourceName"] == "c.docx"


if __name__ == "__main__":
    test_java_callback_retry_drains_successfully()
    test_java_callback_retry_requeues_failed_attempt()
    test_java_callback_retry_moves_to_dlq_after_max_attempts()
    print("PASS java callback retry tests")
