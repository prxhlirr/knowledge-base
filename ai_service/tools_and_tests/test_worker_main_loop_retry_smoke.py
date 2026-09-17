import json
import os
import subprocess
import sys
import time
import unittest
from pathlib import Path

import redis
from elasticsearch import Elasticsearch


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"


class WorkerMainLoopRetrySmokeTest(unittest.TestCase):
    """
    业务功能：启动真实 task_worker.py 主循环，验证失败任务会进入延迟重试集合而不是被立即热循环消费。
    关键流程：清理测试前缀 -> 启动 Worker 子进程 -> 投递缺文件任务 -> 等待 DOC_TASK_RETRY 出现任务 -> 终止子进程。
    设计原因：单次消费函数只能证明局部逻辑，真实主循环必须证明不会把失败任务从低优先队列立刻再次消费到 DLQ。
    """

    @classmethod
    def setUpClass(cls):
        cls.redis = redis.Redis(host="localhost", port=6379, decode_responses=True)
        if not cls.redis.ping():
            raise unittest.SkipTest("Redis 不可达，跳过 Worker 主循环冒烟测试")
        cls.es = Elasticsearch("http://localhost:9200")
        if not cls.es.ping():
            raise unittest.SkipTest("Elasticsearch 不可达，跳过 Worker 主循环冒烟测试")

    def setUp(self):
        self.prefix = f"codex-worker-main-loop-smoke-{int(time.time() * 1000)}"
        self._skip_if_shared_queues_are_busy()
        self._cleanup()

    def tearDown(self):
        self._cleanup()

    def test_main_loop_puts_failed_payload_into_delayed_retry_queue(self):
        env = os.environ.copy()
        env.update({
            "PYTHONUNBUFFERED": "1",
            "REDIS_HOST": "localhost",
            "REDIS_PORT": "6379",
            "ES_HOST": "http://localhost:9200",
            "JAVA_API_BASE": "http://127.0.0.1:65535/api/v1",
            "KB_INTERNAL_TOKEN": "kb-dev-token-change-me-in-prod",
            "DOC_TASK_RETRY_DELAY_SECONDS": "600",
            "ES_SETUP_MODE": "safe",
        })
        process = subprocess.Popen(
            [sys.executable, "task_worker.py"],
            cwd=str(AI_SERVICE),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        try:
            self.redis.lpush("DOC_TASK_QUEUE_HIGH", json.dumps(self._payload(), ensure_ascii=False))
            queued = self._wait_for_retry_payload(timeout=45)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

        self.assertIsNotNone(queued, "Worker 主循环未在超时时间内把失败任务放入 DOC_TASK_RETRY")
        self.assertEqual(queued["retryCount"], 1)
        self.assertIn("Worker cannot access filePath", queued["lastError"])
        self.assertEqual(self._count_own_list_items("DOC_TASK_QUEUE_HIGH"), 0)
        self.assertEqual(self._count_own_list_items("DOC_TASK_QUEUE"), 0)
        self.assertEqual(self._count_own_list_items("DOC_TASK_DLQ"), 0)

    def _payload(self) -> dict:
        return {
            "taskId": f"{self.prefix}-0",
            "fileCode": f"file-{self.prefix}-0",
            "originalName": f"{self.prefix}.txt",
            "filePath": str(WORKSPACE / "scratch" / f"{self.prefix}-missing.txt"),
            "storageMode": "LOCAL_FS",
            "targetIndex": "kb_document_law",
            "deptCode": "620102",
            "ownerUnitCode": "620102",
            "visibleUnitCodes": ["620102", "6201", "62"],
            "permissionVersion": 2026063004,
            "visibility": "INTERNAL",
            "retryCount": 0,
        }

    def _wait_for_retry_payload(self, timeout: int) -> dict | None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            for item in self.redis.zrange("DOC_TASK_RETRY", 0, -1):
                if self.prefix in item:
                    return json.loads(item)
            time.sleep(0.5)
        return None

    def _count_own_list_items(self, queue: str) -> int:
        return sum(1 for item in self.redis.lrange(queue, 0, -1) if self.prefix in item)

    def _skip_if_shared_queues_are_busy(self):
        busy = {
            "DOC_TASK_QUEUE_HIGH": self.redis.llen("DOC_TASK_QUEUE_HIGH"),
            "DOC_TASK_QUEUE": self.redis.llen("DOC_TASK_QUEUE"),
        }
        if any(count > 0 for count in busy.values()):
            raise unittest.SkipTest(f"正式 Worker 队列非空，为避免消费业务任务，本次跳过: {busy}")

    def _cleanup(self):
        for queue in ("DOC_TASK_QUEUE_HIGH", "DOC_TASK_QUEUE", "DOC_TASK_DLQ"):
            for item in self.redis.lrange(queue, 0, -1):
                if self.prefix in item:
                    self.redis.lrem(queue, 0, item)
        for item in self.redis.zrange("DOC_TASK_RETRY", 0, -1):
            if self.prefix in item:
                self.redis.zrem("DOC_TASK_RETRY", item)


if __name__ == "__main__":
    unittest.main()
