import json
import os
import tempfile
import time
import unittest
from pathlib import Path

import redis
from elasticsearch import Elasticsearch


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"


class WorkerQueuePermissionE2ETest(unittest.TestCase):
    """
    业务功能：验证 Redis 正式队列中的文档任务可以被单次消费入口处理，并完整写入单位权限字段。
    关键流程：LPUSH 高优先队列 -> BRPOP 单条消费 -> RAGPipeline 入库 -> 查询 ES 三类索引 -> 清理测试数据。
    设计原因：本用例验证队列协议与权限投影，向量推理已由模型专项测试覆盖，因此使用确定性向量隔离模型资源波动。
    """

    target_index = "kb_document_law"
    owner_unit = "620102"
    visible_units = ["620102", "6201", "62"]
    permission_version = 2026063002

    @classmethod
    def setUpClass(cls):
        os.environ.setdefault("ES_HOST", "http://localhost:9200")
        os.environ.setdefault("REDIS_HOST", "localhost")
        os.environ.setdefault("REDIS_PORT", "6379")
        os.environ.setdefault("JAVA_SERVICE_HOST", "http://127.0.0.1:65535")
        os.environ.setdefault("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
        os.environ.setdefault("ES_SETUP_MODE", "safe")

        import sys
        sys.path.insert(0, str(AI_SERVICE))

        cls.es = Elasticsearch(os.environ["ES_HOST"])
        if not cls.es.ping():
            raise unittest.SkipTest("Elasticsearch 不可达，跳过队列 E2E")
        if not cls.es.indices.exists(index=cls.target_index):
            raise unittest.SkipTest(f"{cls.target_index} 不存在，跳过队列 E2E")

        cls.redis = redis.Redis(
            host=os.environ["REDIS_HOST"],
            port=int(os.environ["REDIS_PORT"]),
            decode_responses=True,
        )
        if not cls.redis.ping():
            raise unittest.SkipTest("Redis 不可达，跳过队列 E2E")

    def setUp(self):
        self.source_name = f"codex_worker_queue_perm_e2e_{int(time.time() * 1000)}.txt"
        self._cleanup_es()
        self._cleanup_queues()

    def tearDown(self):
        self._cleanup_es()
        self._cleanup_queues()

    def test_high_priority_queue_payload_is_consumed_and_permission_fields_are_indexed(self):
        import sys
        sys.path.insert(0, str(AI_SERVICE))
        from core.model_manager import model_manager
        from core.rag_pipeline import RAGPipeline
        from task_worker import QUEUE_HIGH, consume_one_payload_once

        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as tmp:
            tmp.write(
                "关于 Redis 队列权限控制验证的通知\n"
                "第一条 本任务来自 DOC_TASK_QUEUE_HIGH 高优先级队列。\n"
                "第二条 入库时必须使用 payload.targetIndex 指定的索引。\n"
                "第三条 visible_unit_codes 必须写入所有检索相关索引。\n"
                "第四条 本测试完成后会清理 ES 与 Redis 残留。\n"
            )
            file_path = tmp.name

        payload = {
            "taskId": f"codex-worker-queue-perm-e2e-{int(time.time() * 1000)}",
            "fileCode": f"file-{int(time.time() * 1000)}",
            "originalName": self.source_name,
            "filePath": file_path,
            "storageMode": "LOCAL_FS",
            "targetIndex": self.target_index,
            "tag": "法规",
            "deptCode": self.owner_unit,
            "ownerUnitCode": self.owner_unit,
            "visibleUnitCodes": self.visible_units,
            "permissionVersion": self.permission_version,
            "acl_tokens_json": json.dumps([f"dept::{v}" for v in self.visible_units], ensure_ascii=False),
            "visibility": "INTERNAL",
            "owner": "codex-queue-e2e",
            "unit": "兰州市测试单位",
            "docNumber": "兰队测发〔2026〕30号",
            "force_reindex": True,
        }

        try:
            original_encode_dual = model_manager.encode_dual
            model_manager.encode_dual = self._encode_dual_stub
            self.redis.lpush(QUEUE_HIGH, json.dumps(payload, ensure_ascii=False))
            stats = consume_one_payload_once(self.redis, RAGPipeline(), timeout=5, notify_java=False)
        finally:
            model_manager.encode_dual = original_encode_dual
            Path(file_path).unlink(missing_ok=True)

        self.assertIsNotNone(stats)
        self.assertGreater(stats.get("chunkCount", 0), 0, msg=stats)
        self.es.indices.refresh(index=self.target_index)
        self.es.indices.refresh(index="kb_doc_meta_v2")
        self.es.indices.refresh(index="kb_doc_search_v1")

        main_doc = self._single_hit(self.target_index, {"term": {"metadata.source": self.source_name}})
        self._assert_projection(main_doc["_source"])
        self._assert_projection(main_doc["_source"]["metadata"])

        meta_doc = self._single_hit("kb_doc_meta_v2", {"term": {"source": self.source_name}})
        self._assert_projection(meta_doc["_source"])

        search_doc = self._single_hit("kb_doc_search_v1", {"term": {"source": self.source_name}})
        self._assert_projection(search_doc["_source"])

    def _encode_dual_stub(self, texts, top_k=64):
        dense_vectors = [[1.0] + [0.0] * 1023 for _ in texts]
        sparse_vectors = [{"redis": 1.0, "权限": 1.0} for _ in texts]
        return dense_vectors, sparse_vectors

    def _assert_projection(self, source: dict):
        self.assertEqual(source.get("source_index"), self.target_index)
        self.assertEqual(source.get("index_code"), "law")
        self.assertEqual(source.get("owner_unit_code"), self.owner_unit)
        self.assertEqual(source.get("visible_unit_codes"), self.visible_units)
        self.assertEqual(source.get("permission_version"), self.permission_version)

    def _single_hit(self, index: str, query: dict) -> dict:
        result = self.es.search(index=index, body={"query": query, "size": 5})
        hits = result.get("hits", {}).get("hits", [])
        self.assertGreaterEqual(len(hits), 1, msg=f"{index} 未查询到 {self.source_name}")
        return hits[0]

    def _cleanup_es(self):
        cleanup_targets = [
            ("kb_document_law", {"term": {"metadata.source": self.source_name}}),
            ("kb_doc_meta_v2", {"term": {"source": self.source_name}}),
            ("kb_doc_search_v1", {"term": {"source": self.source_name}}),
            ("kb_qa_pairs", {"term": {"source": self.source_name}}),
        ]
        for index, query in cleanup_targets:
            try:
                if self.es.indices.exists(index=index):
                    self.es.delete_by_query(index=index, body={"query": query}, conflicts="proceed", refresh=True)
            except Exception:
                pass

    def _cleanup_queues(self):
        for queue in ("DOC_TASK_QUEUE_HIGH", "DOC_TASK_QUEUE", "QUEUE_QA", "DOC_TASK_DLQ"):
            try:
                for item in self.redis.lrange(queue, 0, -1):
                    if self.source_name in item:
                        self.redis.lrem(queue, 0, item)
            except Exception:
                pass


if __name__ == "__main__":
    unittest.main()
