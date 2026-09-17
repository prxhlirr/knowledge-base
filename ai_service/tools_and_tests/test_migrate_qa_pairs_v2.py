import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_script():
    path = ROOT / "scripts" / "migrate_qa_pairs_v2.py"
    spec = importlib.util.spec_from_file_location("migrate_qa_pairs_v2", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeIndices:
    """
    业务功能：模拟 ES indices 客户端。
    关键流程：记录 create/update_aliases/refresh 调用，验证迁移脚本不会误触真实 ES。
    """

    def __init__(self, exists=False):
        self._exists = exists
        self.created = {}
        self.alias_body = None
        self.refreshed = []

    def exists(self, index):
        return self._exists or index in self.created

    def create(self, index, body):
        self.created[index] = body

    def update_aliases(self, body):
        self.alias_body = body

    def refresh(self, index):
        self.refreshed.append(index)


class FakeEs:
    """
    业务功能：为迁移脚本提供最小 ES 对象。
    关键流程：只暴露测试所需的 indices 属性。
    """

    def __init__(self, exists=False):
        self.indices = FakeIndices(exists=exists)


def test_qa_v2_mapping_defines_permission_fields_as_keyword():
    mod = load_script()

    props = mod.qa_v2_mapping()["mappings"]["properties"]

    assert props["acl_tokens"]["type"] == "keyword"
    assert props["source_index"]["type"] == "keyword"
    assert props["index_code"]["type"] == "keyword"
    assert props["owner_unit_code"]["type"] == "keyword"
    assert props["visible_unit_codes"]["type"] == "keyword"
    assert props["permission_version"]["type"] == "long"
    assert props["question_vector"]["type"] == "dense_vector"
    assert props["question_vector"]["dims"] == 1024


def test_build_target_source_normalizes_legacy_fields_fail_closed():
    mod = load_script()

    target = mod.build_target_source(
        {
            "question": "问",
            "vector": [0.1, 0.2],
            "answer": "答",
            "visible_unit_codes": "A001,ROOT",
            "doc_version": "3",
            "is_latest": True,
        },
        now_ms=12345,
    )

    assert target["question_vector"] == [0.1, 0.2]
    assert target["answer_content"] == "答"
    assert target["acl_tokens"] == ["_NO_ACCESS"]
    assert target["visible_unit_codes"] == ["A001", "ROOT"]
    assert target["permission_version"] == 12345
    assert target["doc_version"] == 3


def test_ensure_target_index_is_dry_run_by_default():
    mod = load_script()
    es = FakeEs(exists=False)

    mod.ensure_target_index(es, "kb_qa_pairs_v2", execute=False)

    assert es.indices.created == {}


def test_ensure_target_index_creates_when_execute_enabled():
    mod = load_script()
    es = FakeEs(exists=False)

    mod.ensure_target_index(es, "kb_qa_pairs_v2", execute=True)

    props = es.indices.created["kb_qa_pairs_v2"]["mappings"]["properties"]
    assert props["acl_tokens"]["type"] == "keyword"


def test_migrate_batch_dry_run_does_not_bulk_write():
    mod = load_script()
    es = FakeEs()
    stats = mod.MigrationStats()

    left = mod.migrate_batch(
        es=es,
        hits=[
            {
                "_id": "qa-1",
                "_source": {
                    "question": "q",
                    "question_vector": [0.1],
                    "answer_content": "a",
                    "acl_tokens": ["_INTERNAL"],
                },
            }
        ],
        target_index="kb_qa_pairs_v2",
        execute=False,
        stats=stats,
        sample_left=0,
    )

    assert left == 0
    assert stats.scanned == 1
    assert stats.dry_run_writes == 1
    assert stats.written == 0


def test_migrate_batch_execute_uses_bulk_index_actions():
    mod = load_script()
    es = FakeEs()
    stats = mod.MigrationStats()
    captured = {}

    def fake_bulk(es_client, actions, raise_on_error=False, refresh=False):
        captured["es"] = es_client
        captured["actions"] = actions
        captured["raise_on_error"] = raise_on_error
        captured["refresh"] = refresh
        return len(actions), []

    original_bulk = mod.helpers.bulk
    mod.helpers.bulk = fake_bulk
    try:
        mod.migrate_batch(
            es=es,
            hits=[
                {
                    "_id": "qa-2",
                    "_source": {
                        "question": "q",
                        "question_vector": [0.1],
                        "answer_content": "a",
                        "acl_tokens": ["_INTERNAL"],
                        "source_index": "kb_document_official",
                    },
                }
            ],
            target_index="kb_qa_pairs_v2",
            execute=True,
            stats=stats,
            sample_left=0,
        )
    finally:
        mod.helpers.bulk = original_bulk

    action = captured["actions"][0]
    assert action["_op_type"] == "index"
    assert action["_index"] == "kb_qa_pairs_v2"
    assert action["_id"] == "qa-2"
    assert action["_source"]["acl_tokens"] == ["_INTERNAL"]
    assert action["_source"]["source_index"] == "kb_document_official"
    assert stats.written == 1


def test_switch_aliases_requires_explicit_switch_flag():
    mod = load_script()
    es = FakeEs()

    mod.switch_aliases(
        es,
        source_index="kb_qa_pairs",
        target_index="kb_qa_pairs_v2",
        read_alias="kb_qa_read",
        write_alias="kb_qa_write",
        execute=True,
        switch_alias=False,
    )

    assert es.indices.alias_body is None


def test_switch_aliases_builds_read_write_alias_actions():
    mod = load_script()
    es = FakeEs()

    mod.switch_aliases(
        es,
        source_index="kb_qa_pairs",
        target_index="kb_qa_pairs_v2",
        read_alias="kb_qa_read",
        write_alias="kb_qa_write",
        execute=True,
        switch_alias=True,
    )

    actions = es.indices.alias_body["actions"]
    assert {"add": {"index": "kb_qa_pairs_v2", "alias": "kb_qa_read"}} in actions
    assert {"add": {"index": "kb_qa_pairs_v2", "alias": "kb_qa_write", "is_write_index": True}} in actions


if __name__ == "__main__":
    tests = [
        test_qa_v2_mapping_defines_permission_fields_as_keyword,
        test_build_target_source_normalizes_legacy_fields_fail_closed,
        test_ensure_target_index_is_dry_run_by_default,
        test_ensure_target_index_creates_when_execute_enabled,
        test_migrate_batch_dry_run_does_not_bulk_write,
        test_migrate_batch_execute_uses_bulk_index_actions,
        test_switch_aliases_requires_explicit_switch_flag,
        test_switch_aliases_builds_read_write_alias_actions,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
