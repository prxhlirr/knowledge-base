import importlib.util
import sys
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load_module():
    path = ROOT / "scripts" / "migrate_kb_document_index_v2.py"
    spec = importlib.util.spec_from_file_location("migrate_kb_document_index_v2", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mod = load_module()


def test_mapping_uses_keyword_for_permission_fields():
    mapping = mod.kb_document_v2_mapping(shards=3, replicas=1)
    props = mapping["mappings"]["properties"]
    meta = props["metadata"]["properties"]

    assert mapping["settings"]["number_of_shards"] == 3
    assert mapping["settings"]["number_of_replicas"] == 1
    assert props["acl_tokens"]["type"] == "keyword"
    assert props["source_index"]["type"] == "keyword"
    assert props["index_code"]["type"] == "keyword"
    assert props["owner_unit_code"]["type"] == "keyword"
    assert props["visible_unit_codes"]["type"] == "keyword"
    assert props["permission_version"]["type"] == "long"
    assert meta["acl_tokens"]["type"] == "keyword"
    assert meta["source_index"]["type"] == "keyword"
    assert meta["visible_unit_codes"]["type"] == "keyword"
    assert props["vector"]["type"] == "dense_vector"
    assert props["vector"]["dims"] == 1024


def test_build_target_source_promotes_permission_fields_to_root_and_metadata():
    source = {
        "content": "正文",
        "vector": [0.1, 0.2],
        "metadata": {
            "source": "doc.pdf",
            "acl_tokens": ["dept::620102"],
            "source_index": "kb_document_news",
            "index_code": "news",
            "owner_unit_code": "620102",
            "visible_unit_codes": ["620102", "6201"],
            "permission_version": "123",
            "is_latest": True,
            "doc_version": "2",
            "title": "标题",
        },
    }

    target = mod.build_target_source(source, source_index="kb_document_news", now_ms=999)

    assert target["acl_tokens"] == ["dept::620102"]
    assert target["source_index"] == "kb_document_news"
    assert target["index_code"] == "news"
    assert target["owner_unit_code"] == "620102"
    assert target["visible_unit_codes"] == ["620102", "6201"]
    assert target["permission_version"] == 123
    assert target["metadata"]["acl_tokens"] == ["dept::620102"]
    assert target["metadata"]["visible_unit_codes"] == ["620102", "6201"]
    assert target["metadata"]["doc_version"] == 2
    assert target["vector"] == [0.1, 0.2]
    assert "colloquial_vector" not in target


def test_build_target_source_fail_closed_when_acl_missing():
    target = mod.build_target_source({"metadata": {"source": "private.pdf"}}, source_index="kb_document_official", now_ms=100)

    assert target["acl_tokens"] == ["_NO_ACCESS"]
    assert target["metadata"]["acl_tokens"] == ["_NO_ACCESS"]
    assert target["source_index"] == "kb_document_official"
    assert target["visible_unit_codes"] == ["global"]
    assert target["permission_version"] == 100
    assert "vector" not in target


class FakeIndices:
    def __init__(self, exists=False):
        self._exists = exists
        self.created = []
        self.alias_updates = []
        self.refreshed = []
        self.aliases = {}

    def exists(self, index):
        return self._exists

    def create(self, index, body):
        self.created.append((index, body))

    def update_aliases(self, body):
        self.alias_updates.append(body)

    def refresh(self, index):
        self.refreshed.append(index)

    def get_alias(self, index=None, name=None):
        # 按别名反查（name only）：返回所有持有该别名的物理索引
        if index is None and name is not None:
            holders = [idx for (idx, al) in self.aliases if al == name]
            if not holders:
                raise Exception("alias missing")
            return {idx: {"aliases": {name: {}}} for idx in holders}
        # 按索引+别名精确查（原有行为）
        if (index, name) not in self.aliases:
            raise Exception("alias missing")
        return {index: {"aliases": {name: {}}}}


class FakeEs:
    def __init__(self, exists=False):
        self.indices = FakeIndices(exists=exists)


def test_ensure_target_index_dry_run_does_not_create():
    es = FakeEs(exists=False)

    mod.ensure_target_index(es, "kb_document_news_v2", execute=False, create_target=True, shards=1, replicas=0)

    assert es.indices.created == []


def test_ensure_target_index_execute_creates_with_mapping():
    es = FakeEs(exists=False)

    mod.ensure_target_index(es, "kb_document_news_v2", execute=True, create_target=True, shards=2, replicas=1)

    assert es.indices.created[0][0] == "kb_document_news_v2"
    assert es.indices.created[0][1]["mappings"]["properties"]["acl_tokens"]["type"] == "keyword"


def test_remove_auto_read_alias_removes_template_alias_before_switch():
    es = FakeEs(exists=True)
    # 迁移前真实状态：source 仍持有读别名，target 从模板继承了读别名
    es.indices.aliases[("kb_document_news", "kb_document")] = True
    es.indices.aliases[("kb_document_news_v2", "kb_document")] = True

    actions = mod.remove_auto_read_alias(
        es,
        target_index="kb_document_news_v2",
        source_index="kb_document_news",
        read_alias="kb_document",
        execute=True,
        switch_alias=False,
    )

    assert actions == [{"remove": {"index": "kb_document_news_v2", "alias": "kb_document"}}]
    assert es.indices.alias_updates[0]["actions"] == actions


def test_remove_auto_read_alias_skips_when_already_switched():
    es = FakeEs(exists=True)
    # 已切换状态：source 不再持有读别名，读别名合法地落在 target 上——绝不能剥离
    es.indices.aliases[("kb_document_news_v2", "kb_document")] = True

    actions = mod.remove_auto_read_alias(
        es,
        target_index="kb_document_news_v2",
        source_index="kb_document_news",
        read_alias="kb_document",
        execute=True,
        switch_alias=False,
    )

    assert actions == []
    assert es.indices.alias_updates == []


def test_migrate_batch_dry_run_does_not_bulk_write():
    es = FakeEs(exists=True)
    stats = mod.MigrationStats()
    hits = [
        {
            "_index": "kb_document_news",
            "_id": "doc1_chunk_0",
            "_source": {"metadata": {"acl_tokens": ["_INTERNAL"]}},
        }
    ]

    left = mod.migrate_batch(es, hits, "kb_document_news", "kb_document_news_v2", False, stats, 0)

    assert left == 0
    assert stats.scanned == 1
    assert stats.dry_run_writes == 1
    assert stats.written == 0


def test_switch_aliases_is_gated_by_execute_and_switch_alias():
    es = FakeEs(exists=True)
    # 切换前：读写别名都在 source 上
    es.indices.aliases[("kb_document_news", "kb_document")] = True
    es.indices.aliases[("kb_document_news", "kb_document_news_write")] = True

    actions = mod.switch_aliases(
        es,
        source_index="kb_document_news",
        target_index="kb_document_news_v2",
        read_alias="kb_document",
        write_alias="kb_document_news_write",
        execute=False,
        switch_alias=True,
    )

    assert es.indices.alias_updates == []
    assert actions[0]["remove"]["index"] == "kb_document_news"
    assert "ignore_unavailable" not in actions[0]["remove"]
    assert actions[2]["add"]["index"] == "kb_document_news_v2"


def test_switch_aliases_refuses_when_batch_has_failures():
    es = FakeEs(exists=True)
    stats = mod.MigrationStats(failed=3)

    actions = mod.switch_aliases(
        es,
        source_index="kb_document_news",
        target_index="kb_document_news_v2",
        read_alias="kb_document",
        write_alias="kb_document_news_write",
        execute=True,
        switch_alias=True,
        stats=stats,
    )

    assert actions == []
    assert es.indices.alias_updates == []


def test_switch_aliases_is_noop_when_already_on_target():
    es = FakeEs(exists=True)
    # 已切换：读写别名都在 target 上
    es.indices.aliases[("kb_document_news_v2", "kb_document")] = True
    es.indices.aliases[("kb_document_news_v2", "kb_document_news_write")] = True

    actions = mod.switch_aliases(
        es,
        source_index="kb_document_news",
        target_index="kb_document_news_v2",
        read_alias="kb_document",
        write_alias="kb_document_news_write",
        execute=True,
        switch_alias=True,
    )

    assert actions == []
    assert es.indices.alias_updates == []


def test_parse_args_accepts_runbook_options():
    argv = [
        "migrate_kb_document_index_v2.py",
        "--source-index",
        "kb_document_news",
        "--target-index",
        "kb_document_news_v2",
        "--es-host",
        "http://es:9200",
        "--batch-size",
        "500",
        "--limit",
        "1000",
        "--create-target",
        "--execute",
        "--output",
        "/tmp/report.json",
    ]
    with patch.object(sys, "argv", argv):
        args = mod.parse_args()

    assert args.source_index == "kb_document_news"
    assert args.target_index == "kb_document_news_v2"
    assert args.es_host == "http://es:9200"
    assert args.batch_size == 500
    assert args.limit == 1000
    assert args.create_target is True
    assert args.execute is True
    assert args.write_alias == "kb_document_news_write"


def test_build_report_contains_structured_stats():
    args = type(
        "Args",
        (),
        {
            "execute": False,
            "source_index": "kb_document_news",
            "target_index": "kb_document_news_v2",
            "es_host": "http://es:9200",
            "batch_size": 100,
            "limit": 10,
            "sample": 1,
            "create_target": True,
            "switch_alias": False,
            "read_alias": "kb_document",
            "write_alias": "kb_document_news_write",
        },
    )()
    stats = mod.MigrationStats(scanned=10, dry_run_writes=10)

    report = mod.build_report(args, stats, 1, 2, [])

    assert report["mode"] == "dry-run"
    assert report["stats"]["scanned"] == 10
    assert report["create_target"] is True
    assert report["alias_actions"] == []


if __name__ == "__main__":
    tests = [
        test_mapping_uses_keyword_for_permission_fields,
        test_build_target_source_promotes_permission_fields_to_root_and_metadata,
        test_build_target_source_fail_closed_when_acl_missing,
        test_ensure_target_index_dry_run_does_not_create,
        test_ensure_target_index_execute_creates_with_mapping,
        test_remove_auto_read_alias_removes_template_alias_before_switch,
        test_remove_auto_read_alias_skips_when_already_switched,
        test_migrate_batch_dry_run_does_not_bulk_write,
        test_switch_aliases_is_gated_by_execute_and_switch_alias,
        test_switch_aliases_refuses_when_batch_has_failures,
        test_switch_aliases_is_noop_when_already_on_target,
        test_parse_args_accepts_runbook_options,
        test_build_report_contains_structured_stats,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
