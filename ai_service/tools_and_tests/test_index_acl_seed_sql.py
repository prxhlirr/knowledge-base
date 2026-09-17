from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SQL_PATH = ROOT / "java_service" / "src" / "main" / "resources" / "db" / "manual" / "20260629_p2_seed_all_document_index_acl_pgsql.sql"


def test_seed_sql_contains_all_current_document_indices():
    sql = SQL_PATH.read_text(encoding="utf-8")
    for index_name in [
        "kb_document_official",
        "kb_document_public",
        "kb_document_law",
        "kb_document_notice",
        "kb_document_v1",
        "kb_document_news",
    ]:
        assert index_name in sql


def test_seed_sql_is_idempotent_and_manual():
    sql = SQL_PATH.read_text(encoding="utf-8")
    assert "WHERE NOT EXISTS" in sql
    assert "BEGIN;" in sql
    assert "COMMIT;" in sql
    assert "manual_p2_seed_all_document_index_acl" in sql
    assert "Optional rollback" in sql


def test_seed_sql_uses_role_read_allow_rows_only():
    sql = SQL_PATH.read_text(encoding="utf-8")
    assert "'ROLE'" in sql
    assert "'READ'" in sql
    assert "'ALLOW'" in sql
    for role_code in [
        "official_reader",
        "public_reader",
        "law_reader",
        "notice_reader",
        "legacy_reader",
        "news_reader",
    ]:
        assert role_code in sql


if __name__ == "__main__":
    tests = [
        test_seed_sql_contains_all_current_document_indices,
        test_seed_sql_is_idempotent_and_manual,
        test_seed_sql_uses_role_read_allow_rows_only,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
