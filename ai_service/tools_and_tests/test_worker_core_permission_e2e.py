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
MODEL_BASE = WORKSPACE / "models" / "onnx_native"


class WorkerCorePermissionE2ETest(unittest.TestCase):
    """
    业务功能：验证 Worker 核心入库链路会把单位权限字段写入主索引、元索引和搜索索引。
    关键流程：构造与 Worker 一致的 payload -> 生成 ext_meta -> 调用 RAGPipeline.process_and_index -> 查询 ES 校验 -> 清理测试数据。
    设计原因：单位权限过滤依赖 ES 中的 visible_unit_codes 字段，必须用真实入库链路验证字段不会在中途丢失。
    """

    source_name = f"codex_worker_perm_e2e_{int(time.time() * 1000)}.txt"
    target_index = "kb_document_law"
    owner_unit = "620102"
    visible_units = ["620102", "6201", "62"]
    permission_version = 2026063001

    @classmethod
    def setUpClass(cls):
        os.environ.setdefault("ES_HOST", "http://localhost:9200")
        os.environ.setdefault("REDIS_HOST", "localhost")
        os.environ.setdefault("REDIS_PORT", "6379")
        os.environ.setdefault("MODEL_BASE_PATH", str(MODEL_BASE))
        os.environ.setdefault("JAVA_SERVICE_HOST", "http://127.0.0.1:65535")
        os.environ.setdefault("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
        os.environ.setdefault("ES_SETUP_MODE", "safe")

        import sys
        sys.path.insert(0, str(AI_SERVICE))

        cls.es = Elasticsearch(os.environ["ES_HOST"])
        if not cls.es.ping():
            raise unittest.SkipTest("Elasticsearch 不可达，跳过真实 ES E2E")
        if not cls.es.indices.exists(index=cls.target_index):
            raise unittest.SkipTest(f"{cls.target_index} 不存在，跳过真实 ES E2E")
        if not MODEL_BASE.exists():
            raise unittest.SkipTest("本地 ONNX 模型目录不存在，跳过真实向量化 E2E")

        cls.redis = redis.Redis(
            host=os.environ["REDIS_HOST"],
            port=int(os.environ["REDIS_PORT"]),
            decode_responses=True,
        )
        if not cls.redis.ping():
            raise unittest.SkipTest("Redis 不可达，跳过真实 Worker 核心 E2E")

    def setUp(self):
        self._cleanup_es()
        self._cleanup_qa_queue()

    def tearDown(self):
        self._cleanup_es()
        self._cleanup_qa_queue()

    def test_worker_core_ingest_writes_permission_projection_to_all_indexes(self):
        from core.permissions.payload_projection import build_permission_projection_from_payload
        from core.rag_pipeline import RAGPipeline

        payload = {
            "taskId": f"codex-worker-perm-e2e-{int(time.time() * 1000)}",
            "originalName": self.source_name,
            "targetIndex": self.target_index,
            "storageMode": "LOCAL_FS",
            "tag": "法规",
            "deptCode": self.owner_unit,
            "ownerUnitCode": self.owner_unit,
            "visibleUnitCodes": self.visible_units,
            "permissionVersion": self.permission_version,
            "acl_tokens_json": json.dumps([f"dept::{v}" for v in self.visible_units], ensure_ascii=False),
            "visibility": "INTERNAL",
            "owner": "codex-e2e",
            "unit": "兰州市测试单位",
            "docNumber": "兰测发〔2026〕30号",
        }
        ext_meta = {
            "tag": payload["tag"],
            "visibility": payload["visibility"],
            "dept_code": payload["deptCode"],
            "acl_tokens_json": payload["acl_tokens_json"],
            "owner": payload["owner"],
            "unit": payload["unit"],
            "docNumber": payload["docNumber"],
            "targetIndex": payload["targetIndex"],
            "task_id": payload["taskId"],
            "data_source": "codex_e2e",
            "force_reindex": True,
        }
        ext_meta.update(build_permission_projection_from_payload(payload))

        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as tmp:
            tmp.write(
                "关于兰州市测试单位权限控制验证的通知\n"
                "第一条 本文档用于验证文档入库时单位权限字段是否完整写入。\n"
                "第二条 文档挂在 A 部门时，应允许 A 部门及上级部门检索到该文档。\n"
                "第三条 visible_unit_codes 必须同时写入主索引、元索引和文档搜索索引。\n"
                "第四条 本测试使用唯一文件名，执行完成后会清理 ES 测试数据。\n"
            )
            file_path = tmp.name

        try:
            stats = RAGPipeline().process_and_index(file_path, original_name=self.source_name, ext_metadata=ext_meta)
        finally:
            Path(file_path).unlink(missing_ok=True)

        self.assertGreater(stats.get("chunkCount", 0), 0, msg=stats)
        self.es.indices.refresh(index=self.target_index)
        self.es.indices.refresh(index="kb_doc_meta_v2")
        self.es.indices.refresh(index="kb_doc_search_v1")

        main_doc = self._single_hit(
            self.target_index,
            {"term": {"metadata.source": self.source_name}},
        )
        self._assert_projection(main_doc["_source"])
        self._assert_projection(main_doc["_source"]["metadata"])

        meta_doc = self._single_hit(
            "kb_doc_meta_v2",
            {"term": {"source": self.source_name}},
        )
        self._assert_projection(meta_doc["_source"])

        search_doc = self._single_hit(
            "kb_doc_search_v1",
            {"term": {"source": self.source_name}},
        )
        self._assert_projection(search_doc["_source"])

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

    def _cleanup_qa_queue(self):
        try:
            for item in self.redis.lrange("QUEUE_QA", 0, -1):
                if self.source_name in item:
                    self.redis.lrem("QUEUE_QA", 0, item)
        except Exception:
            pass


if __name__ == "__main__":
    unittest.main()
