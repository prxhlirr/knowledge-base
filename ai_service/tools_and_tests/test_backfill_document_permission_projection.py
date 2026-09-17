import importlib.util
import sys
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]


def load_script():
    path = ROOT / "scripts" / "backfill_document_permission_projection.py"
    spec = importlib.util.spec_from_file_location("backfill_document_permission_projection", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeRegistry:
    def __init__(self, owner=None):
        self.owner = owner
        self.calls = []

    def find_owner(self, doc_id, source_name):
        self.calls.append((doc_id, source_name))
        return self.owner


class FakeEs:
    pass


def test_missing_permission_query_covers_all_projection_fields():
    mod = load_script()

    should = mod.missing_permission_query()["query"]["bool"]["should"]

    for field in mod.PERMISSION_FIELDS:
        assert {"bool": {"must_not": [{"exists": {"field": field}}]}} in should
    assert {"term": {"source_index": ""}} not in should
    assert {"term": {"index_code": ""}} not in should
    assert {"term": {"owner_unit_code": ""}} not in should


def test_projection_prefers_es_owner_and_physical_index():
    mod = load_script()

    projection = mod.projection_from_hit(
        {
            "_index": "kb_document_official",
            "_id": "doc-1_chunk_0",
            "_source": {
                "metadata": {
                    "owner_dept_id": "620100000000",
                    "visible_depts": ["620100000000", "620000000000"],
                }
            },
        },
        registry=FakeRegistry(owner="should-not-use"),
        default_owner="global",
    )

    assert projection.source_index == "kb_document_official"
    assert projection.index_code == "official"
    assert projection.owner_unit_code == "620100000000"
    assert projection.visible_unit_codes == ["620100000000", "620000000000"]
    assert projection.acl_tokens == ["_INTERNAL"]
    assert projection.owner_source == "es"


def test_projection_uses_pg_registry_when_es_owner_missing():
    mod = load_script()
    registry = FakeRegistry(owner="620200000000")

    projection = mod.projection_from_hit(
        {
            "_index": "kb_document_public",
            "_id": "doc-2_chunk_0",
            "_source": {
                "metadata": {
                    "doc_id": "doc-2",
                    "source": "demo.pdf",
                }
            },
        },
        registry=registry,
        default_owner="global",
    )

    assert registry.calls == [("doc-2", "demo.pdf")]
    assert projection.source_index == "kb_document_public"
    assert projection.index_code == "public"
    assert projection.owner_unit_code == "620200000000"
    assert projection.visible_unit_codes == ["620200000000"]
    assert projection.acl_tokens == ["_INTERNAL"]
    assert projection.owner_source == "pg"


def test_projection_promotes_metadata_acl_tokens():
    mod = load_script()

    projection = mod.projection_from_hit(
        {
            "_index": "kb_document_policy",
            "_id": "doc-20_chunk_0",
            "_source": {
                "metadata": {
                    "owner_dept_id": "620102",
                    "acl_tokens": ["dept::620102", "dept::6201"],
                    "visibility": "DEPT",
                }
            },
        },
        registry=FakeRegistry(owner=None),
        default_owner="global",
    )

    assert projection.acl_tokens == ["dept::620102", "dept::6201"]


def test_projection_fail_closed_when_private_acl_tokens_missing():
    mod = load_script()

    projection = mod.projection_from_hit(
        {
            "_index": "kb_document_private",
            "_id": "doc-21_chunk_0",
            "_source": {
                "metadata": {
                    "owner_dept_id": "620102",
                    "visibility": "PRIVATE",
                }
            },
        },
        registry=FakeRegistry(owner=None),
        default_owner="global",
    )

    assert projection.acl_tokens == ["_NO_ACCESS"]


def test_process_batch_dry_run_does_not_bulk_write():
    mod = load_script()
    stats = mod.BackfillStats()

    sample_left = mod.process_batch(
        es=FakeEs(),
        hits=[
            {
                "_index": "kb_document_news",
                "_id": "doc-3_chunk_0",
                "_source": {"metadata": {"source": "news.pdf"}},
            }
        ],
        execute=False,
        sample_left=0,
        registry=FakeRegistry(owner=None),
        default_owner="global",
        stats=stats,
    )

    assert sample_left == 0
    assert stats.scanned == 1
    assert stats.owner_defaulted == 1
    assert stats.dry_run_updates == 1
    assert stats.written == 0
    assert stats.failed == 0


def test_process_batch_execute_updates_original_physical_index():
    mod = load_script()
    captured = {}

    def fake_bulk(es_client, actions, raise_on_error=False, refresh=False):
        captured["es"] = es_client
        captured["actions"] = actions
        captured["raise_on_error"] = raise_on_error
        captured["refresh"] = refresh
        return len(actions), []

    original_bulk = mod.helpers.bulk
    mod.helpers.bulk = fake_bulk
    stats = mod.BackfillStats()
    es = FakeEs()
    try:
        mod.process_batch(
            es=es,
            hits=[
                {
                    "_index": "kb_document_law",
                    "_id": "doc-4_chunk_0",
                    "_source": {
                        "metadata": {
                            "owner_dept_id": "620300000000",
                        }
                    },
                }
            ],
            execute=True,
            sample_left=0,
            registry=FakeRegistry(owner=None),
            default_owner="global",
            stats=stats,
        )
    finally:
        mod.helpers.bulk = original_bulk

    action = captured["actions"][0]
    assert captured["es"] is es
    assert action["_op_type"] == "update"
    assert action["_index"] == "kb_document_law"
    assert action["_id"] == "doc-4_chunk_0"
    assert action["script"]["params"]["fields"]["source_index"] == "kb_document_law"
    assert action["script"]["params"]["fields"]["index_code"] == "law"
    assert action["script"]["params"]["fields"]["owner_unit_code"] == "620300000000"
    assert action["script"]["params"]["fields"]["visible_unit_codes"] == ["620300000000"]
    assert action["script"]["params"]["fields"]["acl_tokens"] == ["_INTERNAL"]
    assert stats.written == 1
    assert stats.failed == 0


def test_parse_args_accepts_offline_runbook_options():
    mod = load_script()

    with patch.object(sys, "argv", [
        "backfill_document_permission_projection.py",
        "--indices", "kb_document_law",
        "--es-host", "http://es:9200",
        "--es-user", "elastic",
        "--es-pass", "secret",
        "--output", "/tmp/report.json",
        "--no-pg",
    ]):
        args = mod.parse_args()

    assert args.index == "kb_document_law"
    assert args.es_host == "http://es:9200"
    assert args.es_user == "elastic"
    assert args.es_pass == "secret"
    assert args.output == "/tmp/report.json"
    assert args.no_pg is True


def test_build_report_contains_structured_stats():
    mod = load_script()
    stats = mod.BackfillStats(scanned=2, dry_run_updates=2, owner_defaulted=1)

    with patch.object(sys, "argv", ["backfill_document_permission_projection.py", "--indices", "kb_document_*"]):
        args = mod.parse_args()
    report = mod.build_report(args, stats, started_at=1000, finished_at=2000)

    assert report["mode"] == "dry-run"
    assert report["index"] == "kb_document_*"
    assert report["started_at"] == 1000
    assert report["finished_at"] == 2000
    assert report["stats"]["scanned"] == 2
    assert report["stats"]["dry_run_updates"] == 2
    assert report["stats"]["owner_defaulted"] == 1


if __name__ == "__main__":
    tests = [
        test_missing_permission_query_covers_all_projection_fields,
        test_projection_prefers_es_owner_and_physical_index,
        test_projection_uses_pg_registry_when_es_owner_missing,
        test_projection_promotes_metadata_acl_tokens,
        test_projection_fail_closed_when_private_acl_tokens_missing,
        test_process_batch_dry_run_does_not_bulk_write,
        test_process_batch_execute_updates_original_physical_index,
        test_parse_args_accepts_offline_runbook_options,
        test_build_report_contains_structured_stats,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
