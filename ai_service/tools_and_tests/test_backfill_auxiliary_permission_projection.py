import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_script():
    path = ROOT / "scripts" / "backfill_auxiliary_permission_projection.py"
    spec = importlib.util.spec_from_file_location("backfill_auxiliary_permission_projection", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeEs:
    def __init__(self, search_response=None):
        self.search_response = search_response or {"hits": {"hits": []}}
        self.search_calls = []

    def search(self, index, body):
        self.search_calls.append((index, body))
        return self.search_response


def test_missing_projection_query_targets_source_and_unit_fields():
    mod = load_script()

    query = mod.missing_projection_query()["query"]["bool"]

    assert {"bool": {"must_not": [{"exists": {"field": "source_index"}}]}} in query["should"]
    assert {"term": {"source_index": ""}} in query["should"]
    assert {"bool": {"must_not": [{"exists": {"field": "visible_unit_codes"}}]}} in query["should"]
    assert query["filter"] == [mod.latest_filter("is_latest")]


def test_resolve_by_source_uses_chunk_physical_index_and_projection_fields():
    mod = load_script()
    es = FakeEs(
        {
            "hits": {
                "hits": [
                    {
                        "_index": "kb_document_policy",
                        "_source": {
                            "metadata": {
                                "owner_unit_code": "620102",
                                "visible_unit_codes": ["620102", "6201"],
                            }
                        },
                    }
                ]
            }
        }
    )

    projection = mod.resolve_by_source(es, "kb_document", "demo.docx")

    assert projection.source_index == "kb_document_policy"
    assert projection.owner_unit_code == "620102"
    assert projection.visible_unit_codes == ["620102", "6201"]
    index, body = es.search_calls[0]
    assert index == "kb_document"
    should = body["query"]["bool"]["should"]
    assert {"term": {"metadata.source.keyword": "demo.docx"}} in should
    assert {"term": {"source.keyword": "demo.docx"}} in should


def test_build_update_doc_keeps_minimal_permission_projection():
    mod = load_script()

    update_doc = mod.build_update_doc(
        mod.ChunkProjection(
            source_index="kb_document_public",
            owner_unit_code="global",
            visible_unit_codes=["global"],
        )
    )

    assert update_doc["source_index"] == "kb_document_public"
    assert update_doc["index_code"] == "public"
    assert update_doc["owner_unit_code"] == "global"
    assert update_doc["visible_unit_codes"] == ["global"]
    assert isinstance(update_doc["permission_version"], int)


def test_process_batch_execute_updates_original_aux_physical_index():
    mod = load_script()
    captured = {}

    def fake_resolve_by_source(es, chunk_index, source_name):
        assert chunk_index == "kb_document"
        assert source_name == "demo.docx"
        return mod.ChunkProjection(
            source_index="kb_document_policy",
            owner_unit_code="620102",
            visible_unit_codes=["620102", "6201"],
        )

    def fake_bulk(es_client, actions, raise_on_error=False, refresh=False):
        captured["actions"] = actions
        captured["raise_on_error"] = raise_on_error
        captured["refresh"] = refresh
        return len(actions), []

    original_resolve = mod.resolve_by_source
    original_bulk = mod.helpers.bulk
    mod.resolve_by_source = fake_resolve_by_source
    mod.helpers.bulk = fake_bulk
    stats = mod.BackfillStats()
    try:
        mod.process_batch(
            es=FakeEs(),
            aux_hits=[
                {
                    "_index": "kb_doc_search_v1",
                    "_id": "aux-1",
                    "_source": {"source": "demo.docx"},
                }
            ],
            aux_index="kb_doc_search",
            chunk_index="kb_document",
            execute=True,
            sample_left=0,
            stats=stats,
        )
    finally:
        mod.resolve_by_source = original_resolve
        mod.helpers.bulk = original_bulk

    action = captured["actions"][0]
    assert action["_op_type"] == "update"
    assert action["_index"] == "kb_doc_search_v1"
    assert action["_id"] == "aux-1"
    assert action["script"]["params"]["fields"]["source_index"] == "kb_document_policy"
    assert action["script"]["params"]["fields"]["visible_unit_codes"] == ["620102", "6201"]
    assert stats.written == 1
    assert stats.failed == 0


def test_source_projection_query_targets_one_document_source():
    mod = load_script()

    query = mod.source_projection_query("demo.docx")["query"]["bool"]

    assert query["filter"] == [mod.latest_filter("is_latest")]
    assert {"term": {"source.keyword": "demo.docx"}} in query["should"]
    assert {"term": {"source_name.keyword": "demo.docx"}} in query["should"]
    assert query["minimum_should_match"] == 1


def test_backfill_source_scans_each_aux_index_and_executes_updates():
    mod = load_script()
    captured = {"indexes": []}

    def fake_scan_aux_docs_by_source(es, aux_index, source_name, batch_size):
        captured["indexes"].append(aux_index)
        assert source_name == "demo.docx"
        yield [{"_index": aux_index + "_v1", "_id": aux_index + "-1", "_source": {"source": "demo.docx"}}]

    def fake_resolve_by_source(es, chunk_index, source_name):
        assert chunk_index == "kb_document"
        return mod.ChunkProjection(
            source_index="kb_document_policy",
            owner_unit_code="620102",
            visible_unit_codes=["620102", "6201"],
        )

    def fake_bulk(es_client, actions, raise_on_error=False, refresh=False):
        return len(actions), []

    original_scan = mod.scan_aux_docs_by_source
    original_resolve = mod.resolve_by_source
    original_bulk = mod.helpers.bulk
    mod.scan_aux_docs_by_source = fake_scan_aux_docs_by_source
    mod.resolve_by_source = fake_resolve_by_source
    mod.helpers.bulk = fake_bulk
    try:
        stats = mod.backfill_source(
            es=FakeEs(),
            source_name="demo.docx",
            aux_indexes=["kb_doc_search", "kb_doc_meta"],
            chunk_index="kb_document",
            execute=True,
        )
    finally:
        mod.scan_aux_docs_by_source = original_scan
        mod.resolve_by_source = original_resolve
        mod.helpers.bulk = original_bulk

    assert captured["indexes"] == ["kb_doc_search", "kb_doc_meta"]
    assert stats.scanned == 2
    assert stats.written == 2


if __name__ == "__main__":
    tests = [
        test_missing_projection_query_targets_source_and_unit_fields,
        test_resolve_by_source_uses_chunk_physical_index_and_projection_fields,
        test_build_update_doc_keeps_minimal_permission_projection,
        test_process_batch_execute_updates_original_aux_physical_index,
        test_source_projection_query_targets_one_document_source,
        test_backfill_source_scans_each_aux_index_and_executes_updates,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
