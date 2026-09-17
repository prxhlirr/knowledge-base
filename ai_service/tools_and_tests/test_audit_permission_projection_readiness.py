import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def load_script():
    """
    业务功能：按文件路径加载权限投影审计脚本。
    关键流程：使用 importlib 避免依赖包安装形态。
    设计原因：脚本位于 scripts 目录，测试应直接验证真实文件内容。
    """
    path = ROOT / "ai_service" / "scripts" / "audit_permission_projection_readiness.py"
    spec = importlib.util.spec_from_file_location("audit_permission_projection_readiness", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_missing_field_query_uses_latest_filter_and_missing_exists():
    mod = load_script()

    query = mod.missing_field_query("source_index", "metadata.is_latest")
    bool_query = query["query"]["bool"]

    assert mod.latest_filter("metadata.is_latest") in bool_query["filter"]
    assert {"exists": {"field": "source_index"}} in bool_query["must_not"]


def test_audit_targets_keep_chunk_and_auxiliary_latest_paths_separate():
    mod = load_script()

    targets = {target["name"]: target for target in mod.audit_targets()}

    assert targets["chunk"]["latest_field"] == "metadata.is_latest"
    assert targets["doc_search"]["latest_field"] == "is_latest"
    assert targets["doc_meta"]["latest_field"] == "is_latest"
    assert targets["qa"]["latest_field"] == "is_latest"


def test_has_risk_is_fail_closed_for_missing_or_unknown_counts():
    mod = load_script()

    assert mod.has_risk({"chunk": {"missing": {"acl_tokens": 1}}}) is True
    assert mod.has_risk({"chunk": {"missing": {"acl_tokens": None}}}) is True
    assert mod.has_risk({"chunk": {"missing": {"acl_tokens": 0, "source_index": 0}}}) is False


if __name__ == "__main__":
    test_missing_field_query_uses_latest_filter_and_missing_exists()
    test_audit_targets_keep_chunk_and_auxiliary_latest_paths_separate()
    test_has_risk_is_fail_closed_for_missing_or_unknown_counts()
    print("PASS permission projection readiness audit tests")
