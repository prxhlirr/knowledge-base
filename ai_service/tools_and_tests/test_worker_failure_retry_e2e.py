import json
import os
import time
import unittest
from pathlib import Path

import redis


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"


class WorkerFailureRetryE2ETest(unittest.TestCase):
    """
    业务功能：验证 Worker 失败路径会按 retryCount 写入延迟重试队列或进入死信队列。
    关键流程：构造不存在的 LOCAL_FS 文件路径 -> 高优先队列入队 -> 单次消费 -> 校验目标队列。
    设计原因：成功路径已覆盖权限字段入库，失败路径必须防止任务丢失、无限重试或错误进入 DLQ。
    """

    @classmethod
    def setUpClass(cls):
        os.environ.setdefault("REDIS_HOST", "localhost")
        os.environ.setdefault("REDIS_PORT", "6379")
        import sys
        sys.path.insert(0, str(AI_SERVICE))
        cls.redis = redis.Redis(
            host=os.environ["REDIS_HOST"],
            port=int(os.environ["REDIS_PORT"]),
            decode_responses=True,
        )
        if not cls.redis.ping():
            raise unittest.SkipTest("Redis 不可达，跳过失败路径 E2E")

    def setUp(self):
        self.prefix = f"codex-worker-failure-e2e-{int(time.time() * 1000)}"
        self._cleanup_queues()

    def tearDown(self):
        self._cleanup_queues()

    def test_missing_file_goes_to_delayed_retry_before_max_retry(self):
        from task_worker import QUEUE_HIGH, QUEUE_LOW, QUEUE_RETRY, consume_one_payload_with_retry

        payload = self._payload(retry_count=0)
        self.redis.lpush(QUEUE_HIGH, json.dumps(payload, ensure_ascii=False))

        result = consume_one_payload_with_retry(self.redis, _UnusedPipeline(), timeout=5, notify_java=False)

        self.assertEqual(result, "RETRY")
        self.assertEqual(self.redis.llen(QUEUE_HIGH), 0)
        self.assertEqual(self.redis.llen(QUEUE_LOW), 0)
        self.assertEqual(self.redis.zcard(QUEUE_RETRY), 1)
        queued = json.loads(self.redis.zrange(QUEUE_RETRY, 0, 0)[0])
        self.assertEqual(queued["taskId"], payload["taskId"])
        self.assertEqual(queued["retryCount"], 1)
        self.assertIn("Worker cannot access filePath", queued["lastError"])

    def test_due_retry_payload_moves_back_to_low_priority_queue(self):
        from task_worker import QUEUE_LOW, QUEUE_RETRY, drain_due_retry_payloads

        payload = self._payload(retry_count=1)
        self.redis.zadd(QUEUE_RETRY, {json.dumps(payload, ensure_ascii=False): time.time() - 1})

        moved = drain_due_retry_payloads(self.redis)

        self.assertEqual(moved, 1)
        self.assertEqual(self.redis.zcard(QUEUE_RETRY), 0)
        self.assertEqual(self.redis.llen(QUEUE_LOW), 1)
        queued = json.loads(self.redis.lindex(QUEUE_LOW, 0))
        self.assertEqual(queued["taskId"], payload["taskId"])

    def test_missing_file_goes_to_dlq_at_max_retry(self):
        from task_worker import QUEUE_HIGH, QUEUE_DLQ, MAX_RETRY, consume_one_payload_with_retry

        payload = self._payload(retry_count=MAX_RETRY)
        self.redis.lpush(QUEUE_HIGH, json.dumps(payload, ensure_ascii=False))

        result = consume_one_payload_with_retry(self.redis, _UnusedPipeline(), timeout=5, notify_java=False)

        self.assertEqual(result, "DLQ")
        self.assertEqual(self.redis.llen(QUEUE_HIGH), 0)
        self.assertEqual(self.redis.llen(QUEUE_DLQ), 1)
        queued = json.loads(self.redis.lindex(QUEUE_DLQ, 0))
        self.assertEqual(queued["taskId"], payload["taskId"])
        self.assertEqual(queued["retryCount"], MAX_RETRY)

    def _payload(self, retry_count: int) -> dict:
        return {
            "taskId": f"{self.prefix}-{retry_count}",
            "fileCode": f"file-{self.prefix}-{retry_count}",
            "originalName": f"{self.prefix}.txt",
            "filePath": str(WORKSPACE / "scratch" / f"{self.prefix}-missing.txt"),
            "storageMode": "LOCAL_FS",
            "targetIndex": "kb_document_law",
            "deptCode": "620102",
            "ownerUnitCode": "620102",
            "visibleUnitCodes": ["620102", "6201", "62"],
            "permissionVersion": 2026063003,
            "visibility": "INTERNAL",
            "retryCount": retry_count,
        }

    def _cleanup_queues(self):
        for queue in ("DOC_TASK_QUEUE_HIGH", "DOC_TASK_QUEUE", "DOC_TASK_DLQ"):
            try:
                for item in self.redis.lrange(queue, 0, -1):
                    if self.prefix in item:
                        self.redis.lrem(queue, 0, item)
            except Exception:
                pass
        try:
            for item in self.redis.zrange("DOC_TASK_RETRY", 0, -1):
                if self.prefix in item:
                    self.redis.zrem("DOC_TASK_RETRY", item)
        except Exception:
            pass


class _UnusedPipeline:
    """
    业务功能：占位 Pipeline，确保缺文件错误发生在调用真实 RAGPipeline 前。
    关键流程：如果测试错误地进入 process_and_index，立即失败。
    设计原因：失败路径测试应快速验证队列编排，不应加载模型或写入 ES。
    """

    def process_and_index(self, *args, **kwargs):
        raise AssertionError("缺文件失败路径不应调用 RAGPipeline.process_and_index")


if __name__ == "__main__":
    unittest.main()
