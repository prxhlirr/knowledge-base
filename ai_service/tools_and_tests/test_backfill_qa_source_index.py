import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_script():
    path = ROOT / "scripts" / "backfill_qa_source_index.py"
    spec = importlib.util.spec_from_file_location("backfill_qa_source_index", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeEs:
    def __init__(self, mget_docs=None):
        self.mget_docs = mget_docs or []
        self.search_calls = []

    def mget(self, index, body, _source_includes=None):
        self.last_mget = {
            "index": index,
            "body": body,
            "_source_includes": _source_includes,
        }
        return {"docs": self.mget_docs}

    def search(self, index, body):
        self.search_calls.append({"index": index, "body": body})
        return {"hits": {"hits": []}}


def test_projection_from_chunk_hit_prefers_physical_index_and_metadata_units():
    mod = load_script()

    projection = mod.projection_from_chunk_hit(
        {
            "_index": "kb_document_policy",
            "_source": {
                "metadata": {
                    "owner_dept_id": "A001",
                    "visible_depts": ["A001", "ROOT"],
                }
            },
        }
    )

    assert projection.source_index == "kb_document_policy"
    assert projection.owner_unit_code == "A001"
    assert projection.visible_unit_codes == ["A001", "ROOT"]
    assert mod.build_update_doc(projection)["index_code"] == "policy"


def test_missing_source_index_query_covers_missing_and_empty_source_index():
    mod = load_script()

    query = mod.missing_source_index_query()["query"]["bool"]

    assert query["minimum_should_match"] == 1
    assert {"term": {"source_index": ""}} in query["should"]
    assert {"bool": {"must_not": [{"exists": {"field": "source_index"}}]}} in query["should"]


def test_process_batch_dry_run_resolves_by_answer_chunk_id_without_bulk_write():
    mod = load_script()
    es = FakeEs(
        mget_docs=[
            {
                "_id": "hash_v2_fine_0",
                "_index": "kb_document_law",
                "found": True,
                "_source": {
                    "metadata": {
                        "owner_dept_id": "D100",
                        "visible_depts": "D100,ROOT",
                    }
                },
            }
        ]
    )
    stats = mod.BackfillStats()
    qa_hits = [
        {
            "_index": "kb_qa_pairs_v2",
            "_id": "qa-1",
            "_source": {
                "answer_chunk_id": "hash_v2_fine_0",
                "source": "doc-a.pdf",
            },
        }
    ]

    mod.process_batch(
        es=es,
        qa_hits=qa_hits,
        qa_index="kb_qa_read",
        chunk_index="kb_document",
        execute=False,
        sample_left=0,
        stats=stats,
    )

    assert es.last_mget["index"] == "kb_document"
    assert stats.scanned == 1
    assert stats.resolved_by_chunk_id == 1
    assert stats.resolved_by_source == 0
    assert stats.dry_run_updates == 1
    assert stats.written == 0
    assert stats.failed == 0


def test_process_batch_execute_uses_hit_index_for_update():
    mod = load_script()
    es = FakeEs(
        mget_docs=[
            {
                "_id": "hash_v3_fine_1",
                "_index": "kb_document_notice",
                "found": True,
                "_source": {
                    "metadata": {
                        "owner_dept_id": "D200",
                        "visible_depts": ["D200"],
                    }
                },
            }
        ]
    )
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

    try:
        mod.process_batch(
            es=es,
            qa_hits=[
                {
                    "_index": "kb_qa_pairs_v3",
                    "_id": "qa-2",
                    "_source": {"answer_chunk_id": "hash_v3_fine_1"},
                }
            ],
            qa_index="kb_qa_read",
            chunk_index="kb_document",
            execute=True,
            sample_left=0,
            stats=stats,
        )
    finally:
        mod.helpers.bulk = original_bulk

    action = captured["actions"][0]
    assert captured["es"] is es
    assert action["_op_type"] == "update"
    assert action["_index"] == "kb_qa_pairs_v3"
    assert action["_id"] == "qa-2"
    assert action["doc"]["source_index"] == "kb_document_notice"
    assert action["doc"]["index_code"] == "notice"
    assert action["doc"]["owner_unit_code"] == "D200"
    assert action["doc"]["visible_unit_codes"] == ["D200"]
    assert stats.written == 1
    assert stats.failed == 0


if __name__ == "__main__":
    tests = [
        test_projection_from_chunk_hit_prefers_physical_index_and_metadata_units,
        test_missing_source_index_query_covers_missing_and_empty_source_index,
        test_process_batch_dry_run_resolves_by_answer_chunk_id_without_bulk_write,
        test_process_batch_execute_uses_hit_index_for_update,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
