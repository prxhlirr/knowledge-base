import importlib.util
import json
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def load_script():
    """
    业务功能：按文件路径加载 mapping readiness 审计脚本。
    关键流程：使用 importlib 直接执行真实脚本，避免测试到复制版本。
    设计原因：该脚本会在 Docker 离线环境中独立执行，测试必须覆盖真实文件入口。
    """
    path = ROOT / "ai_service" / "scripts" / "audit_es_mapping_readiness.py"
    spec = importlib.util.spec_from_file_location("audit_es_mapping_readiness", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def valid_mapping(index="kb_document_law"):
    return {
        index: {
            "mappings": {
                "properties": {
                    "content": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart",
                    },
                    "display_content": {"type": "text"},
                    "vector": {
                        "type": "dense_vector",
                        "dims": 1024,
                        "similarity": "cosine",
                        "index": True,
                    },
                    "sparse_vector": {"type": "rank_features"},
                    "acl_tokens": {"type": "keyword"},
                    "source_index": {"type": "keyword"},
                    "index_code": {"type": "keyword"},
                    "owner_unit_code": {"type": "keyword"},
                    "visible_unit_codes": {"type": "keyword"},
                    "permission_version": {"type": "long"},
                    "metadata": {
                        "properties": {
                            "is_latest": {"type": "boolean"},
                            "doc_version": {"type": "integer"},
                            "acl_tokens": {"type": "keyword"},
                            "source_index": {"type": "keyword"},
                            "visible_unit_codes": {"type": "keyword"},
                            "permission_version": {"type": "long"},
                        }
                    },
                }
            }
        }
    }


def settings(index="kb_document_law", shards="3", replicas="1"):
    return {
        index: {
            "settings": {
                "index": {
                    "number_of_shards": shards,
                    "number_of_replicas": replicas,
                }
            }
        }
    }


def test_valid_mapping_is_ready():
    mod = load_script()

    result = mod.check_mapping(
        "kb_document_law",
        valid_mapping(),
        settings(),
        production=True,
    )

    assert result["ready"] is True
    assert result["errors"] == []


def test_acl_tokens_must_be_keyword():
    mod = load_script()
    mapping = valid_mapping()
    mapping["kb_document_law"]["mappings"]["properties"]["acl_tokens"] = {
        "type": "text",
        "fields": {"keyword": {"type": "keyword"}},
    }

    result = mod.check_mapping(
        "kb_document_law",
        mapping,
        settings(),
        production=False,
    )

    assert result["ready"] is False
    assert any(issue["field"] == "acl_tokens" and issue["code"] == "type_mismatch" for issue in result["errors"])


def test_metadata_latest_and_doc_version_are_checked():
    mod = load_script()
    mapping = valid_mapping()
    del mapping["kb_document_law"]["mappings"]["properties"]["metadata"]["properties"]["is_latest"]

    result = mod.check_mapping(
        "kb_document_law",
        mapping,
        settings(),
        production=False,
    )

    assert result["ready"] is False
    assert any(issue["field"] == "metadata.is_latest" for issue in result["errors"])


def test_zero_replica_is_error_only_in_production():
    mod = load_script()

    dev_result = mod.check_mapping(
        "kb_document_law",
        valid_mapping(),
        settings(replicas="0"),
        production=False,
    )
    prod_result = mod.check_mapping(
        "kb_document_law",
        valid_mapping(),
        settings(replicas="0"),
        production=True,
    )

    assert dev_result["ready"] is True
    assert any(issue["code"] == "zero_replica" for issue in dev_result["warnings"])
    assert prod_result["ready"] is False
    assert any(issue["code"] == "zero_replica" for issue in prod_result["errors"])


def test_has_risk_tracks_not_ready_indices():
    mod = load_script()

    assert mod.has_risk({"indices": {"a": {"ready": True}}}) is False
    assert mod.has_risk({"indices": {"a": {"ready": False}}}) is True


def test_run_audit_from_export_reads_mapping_and_settings():
    mod = load_script()
    index = "kb_document_law"
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        (tmp_path / f"{index}.mapping.json").write_text(
            json.dumps(valid_mapping(index), ensure_ascii=False),
            encoding="utf-8",
        )
        (tmp_path / f"{index}.settings.json").write_text(
            json.dumps(settings(index), ensure_ascii=False),
            encoding="utf-8",
        )

        result = mod.run_audit_from_export(str(tmp_path), "kb_document_*", production=True)

    assert result["summary"]["total"] == 1
    assert result["indices"][index]["ready"] is True


if __name__ == "__main__":
    tests = [
        test_valid_mapping_is_ready,
        test_acl_tokens_must_be_keyword,
        test_metadata_latest_and_doc_version_are_checked,
        test_zero_replica_is_error_only_in_production,
        test_has_risk_tracks_not_ready_indices,
        test_run_audit_from_export_reads_mapping_and_settings,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
