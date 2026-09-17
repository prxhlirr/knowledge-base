import os
import sys
import unittest
import contextlib
import io
import importlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from core.indexing import es_setup


def _clear_env(keys):
    for key in keys:
        os.environ.pop(key, None)


class FakeTemplateIndices:
    """
    业务功能：捕获 ESSetup 注册的索引模板请求。
    关键流程：用内存对象替代真实 ES，确保测试只验证模板结构，不依赖外部服务。
    """

    def __init__(self):
        self.template_body = None

    def put_index_template(self, name, body):
        self.template_name = name
        self.template_body = body

    def get_alias(self, index=None, ignore_unavailable=False, name=None):
        return {}


class FakeCreateIndices:
    """
    业务功能：捕获 ESSetup 创建索引时提交的 mapping。
    关键流程：通过 exists 控制索引是否存在，用 create 记录请求体，避免测试连接真实 ES。
    """

    def __init__(self):
        self.created = {}

    def exists(self, index):
        return index in self.created

    def create(self, index, body):
        self.created[index] = body

    def update_aliases(self, body):
        self.alias_actions = body.get("actions", [])

    def get_alias(self, index=None, ignore_unavailable=False, name=None):
        return {index: {"aliases": {}}} if index else {}


class FakeCreateEs:
    """
    业务功能：为索引创建路径提供最小 ES 客户端。
    关键流程：只实现当前测试覆盖的 exists/create，保证测试聚焦 mapping 结构。
    """

    def __init__(self):
        self.indices = FakeCreateIndices()


class FakeTemplateEs:
    """
    业务功能：为 ESSetup 提供最小 ES 客户端能力。
    关键流程：只实现 _ensure_template 需要的方法，避免测试误触真实 Elasticsearch。
    """

    def __init__(self):
        self.indices = FakeTemplateIndices()


class ESSetupIndexSettingsTest(unittest.TestCase):
    def setUp(self):
        self.keys = [
            "KB_DOCUMENT_SHARDS",
            "KB_DOCUMENT_REPLICAS",
            "KB_DOC_META_SHARDS",
            "KB_DOC_META_REPLICAS",
            "KB_DOC_SEARCH_SHARDS",
            "KB_DOC_SEARCH_REPLICAS",
            "KB_DOC_SEARCH_MAX_NGRAM_DIFF",
            "KB_QA_SHARDS",
            "KB_QA_REPLICAS",
            "APP_ENV",
            "ENV",
            "ENVIRONMENT",
            "PYTHON_ENV",
            "SPRING_PROFILES_ACTIVE",
            "ALLOW_SINGLE_NODE_ES",
        ]
        _clear_env(self.keys)

    def tearDown(self):
        _clear_env(self.keys)

    def test_index_settings_keep_single_node_defaults(self):
        self.assertEqual(es_setup.document_index_settings()["number_of_shards"], 1)
        self.assertEqual(es_setup.document_index_settings()["number_of_replicas"], 0)
        self.assertEqual(es_setup.doc_meta_index_mapping()["settings"]["number_of_shards"], 1)
        self.assertEqual(es_setup.doc_meta_index_mapping()["settings"]["number_of_replicas"], 0)
        self.assertEqual(es_setup.doc_search_index_mapping()["settings"]["number_of_shards"], 1)
        self.assertEqual(es_setup.doc_search_index_mapping()["settings"]["number_of_replicas"], 0)
        self.assertEqual(es_setup.doc_search_index_mapping()["settings"]["max_ngram_diff"], 6)
        self.assertEqual(es_setup.qa_index_mapping()["settings"]["number_of_shards"], 1)
        self.assertEqual(es_setup.qa_index_mapping()["settings"]["number_of_replicas"], 0)

    def test_index_settings_can_be_configured_for_new_indices(self):
        os.environ["KB_DOCUMENT_SHARDS"] = "12"
        os.environ["KB_DOCUMENT_REPLICAS"] = "1"
        os.environ["KB_DOC_META_SHARDS"] = "6"
        os.environ["KB_DOC_META_REPLICAS"] = "1"
        os.environ["KB_DOC_SEARCH_SHARDS"] = "4"
        os.environ["KB_DOC_SEARCH_REPLICAS"] = "1"
        os.environ["KB_DOC_SEARCH_MAX_NGRAM_DIFF"] = "8"
        os.environ["KB_QA_SHARDS"] = "3"
        os.environ["KB_QA_REPLICAS"] = "1"

        self.assertEqual(es_setup.document_index_settings()["number_of_shards"], 12)
        self.assertEqual(es_setup.document_index_settings()["number_of_replicas"], 1)
        self.assertEqual(es_setup.doc_meta_index_mapping()["settings"]["number_of_shards"], 6)
        self.assertEqual(es_setup.doc_meta_index_mapping()["settings"]["number_of_replicas"], 1)
        self.assertEqual(es_setup.doc_search_index_mapping()["settings"]["number_of_shards"], 4)
        self.assertEqual(es_setup.doc_search_index_mapping()["settings"]["number_of_replicas"], 1)
        self.assertEqual(es_setup.doc_search_index_mapping()["settings"]["max_ngram_diff"], 8)
        self.assertEqual(es_setup.qa_index_mapping()["settings"]["number_of_shards"], 3)
        self.assertEqual(es_setup.qa_index_mapping()["settings"]["number_of_replicas"], 1)

    def test_invalid_index_settings_fall_back_to_safe_defaults(self):
        os.environ["KB_DOCUMENT_SHARDS"] = "0"
        os.environ["KB_DOCUMENT_REPLICAS"] = "-1"
        os.environ["KB_DOC_META_SHARDS"] = "abc"
        os.environ["KB_DOC_SEARCH_MAX_NGRAM_DIFF"] = "0"

        self.assertEqual(es_setup.document_index_settings()["number_of_shards"], 1)
        self.assertEqual(es_setup.document_index_settings()["number_of_replicas"], 0)
        self.assertEqual(es_setup.doc_meta_index_settings()["number_of_shards"], 1)
        self.assertEqual(es_setup.doc_search_index_settings()["max_ngram_diff"], 6)

    def test_production_settings_reject_single_node_defaults(self):
        os.environ["APP_ENV"] = "prod"

        with self.assertRaises(RuntimeError) as ctx:
            es_setup.validate_production_index_settings()

        self.assertIn("KB_DOCUMENT_SHARDS=1", str(ctx.exception))
        self.assertIn("KB_DOCUMENT_REPLICAS=0", str(ctx.exception))

    def test_production_settings_can_be_explicitly_bypassed_for_offline_single_node(self):
        os.environ["APP_ENV"] = "prod"
        os.environ["ALLOW_SINGLE_NODE_ES"] = "true"

        es_setup.validate_production_index_settings()

    def test_document_template_uses_configurable_settings(self):
        os.environ["KB_DOCUMENT_SHARDS"] = "8"
        os.environ["KB_DOCUMENT_REPLICAS"] = "1"

        fake_es = FakeTemplateEs()
        with contextlib.redirect_stdout(io.StringIO()):
            es_setup.ESSetup(fake_es)._ensure_template()

        settings = fake_es.indices.template_body["template"]["settings"]
        self.assertEqual(settings["number_of_shards"], 8)
        self.assertEqual(settings["number_of_replicas"], 1)
        self.assertIn("analysis", settings)

    def test_permission_projection_fields_are_explicit_keywords(self):
        expected_keyword_fields = [
            "acl_tokens",
            "source_index",
            "index_code",
            "owner_unit_code",
            "visible_unit_codes",
        ]

        meta_props = es_setup.doc_meta_index_mapping()["mappings"]["properties"]
        search_props = es_setup.doc_search_index_mapping()["mappings"]["properties"]
        for field in expected_keyword_fields:
            self.assertEqual(meta_props[field]["type"], "keyword")
            self.assertEqual(search_props[field]["type"], "keyword")
        self.assertEqual(meta_props["permission_version"]["type"], "long")
        self.assertEqual(search_props["permission_version"]["type"], "long")

        fake_es = FakeTemplateEs()
        with contextlib.redirect_stdout(io.StringIO()):
            es_setup.ESSetup(fake_es)._ensure_template()
        chunk_props = fake_es.indices.template_body["template"]["mappings"]["properties"]
        metadata_props = chunk_props["metadata"]["properties"]
        for field in expected_keyword_fields:
            self.assertEqual(chunk_props[field]["type"], "keyword")
            self.assertEqual(metadata_props[field]["type"], "keyword")
        self.assertEqual(chunk_props["permission_version"]["type"], "long")
        self.assertEqual(metadata_props["permission_version"]["type"], "long")

    def test_created_document_and_qa_indices_define_acl_tokens_as_keyword(self):
        fake_es = FakeCreateEs()
        setup = es_setup.ESSetup(fake_es)

        with contextlib.redirect_stdout(io.StringIO()):
            setup._ensure_index()
            setup._ensure_qa_index()

        doc_props = fake_es.indices.created[es_setup.INDEX_NAME]["mappings"]["properties"]
        qa_props = fake_es.indices.created[es_setup.QA_INDEX_NAME]["mappings"]["properties"]

        self.assertEqual(doc_props["acl_tokens"]["type"], "keyword")
        self.assertEqual(doc_props["metadata"]["properties"]["acl_tokens"]["type"], "keyword")
        self.assertEqual(qa_props["acl_tokens"]["type"], "keyword")
        self.assertEqual(fake_es.indices.created[es_setup.QA_INDEX_NAME]["settings"]["number_of_shards"], 1)
        self.assertEqual(fake_es.indices.created[es_setup.QA_INDEX_NAME]["settings"]["number_of_replicas"], 0)

    def test_legacy_rag_pipeline_template_keeps_permission_fields(self):
        rag_pipeline = ROOT / "core" / "rag_pipeline.py"
        source = rag_pipeline.read_text(encoding="utf-8")

        self.assertIn('"acl_tokens":        {"type": "keyword"}', source)
        self.assertIn('"metadata": {', source)
        self.assertIn('"source_index":      {"type": "keyword"}', source)
        self.assertIn('"visible_unit_codes": {"type": "keyword"}', source)
        self.assertIn('"permission_version": {"type": "long"}', source)
        self.assertIn("document_index_settings()", source)
        self.assertIn("qa_index_mapping()", source)
        self.assertNotIn('"number_of_shards": 1', source)
        self.assertNotIn('"number_of_replicas": 0', source)

    def test_migration_mapping_reuses_document_index_settings(self):
        os.environ["KB_DOCUMENT_SHARDS"] = "16"
        os.environ["KB_DOCUMENT_REPLICAS"] = "1"

        migration = importlib.import_module("core.indexing.es_migration")
        mapping = migration.build_index_mapping()

        self.assertEqual(mapping["settings"]["number_of_shards"], 16)
        self.assertEqual(mapping["settings"]["number_of_replicas"], 1)
        self.assertEqual(mapping["settings"]["index.max_result_window"], 50000)


if __name__ == "__main__":
    unittest.main()
