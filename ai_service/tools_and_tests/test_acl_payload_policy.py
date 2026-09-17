import sys
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"
sys.path.insert(0, str(AI_SERVICE))

from core.permissions.acl_payload import resolve_acl_tokens_from_metadata


def test_valid_acl_tokens_json_is_used_first():
    tokens = resolve_acl_tokens_from_metadata({
        "acl_tokens_json": '["_INTERNAL", "role::reader"]',
        "acl_tokens": "_PUBLIC",
    })

    assert tokens == ["_INTERNAL", "role::reader"]


def test_legacy_acl_tokens_string_is_supported():
    tokens = resolve_acl_tokens_from_metadata({
        "acl_tokens": "_INTERNAL, role::reader",
    })

    assert tokens == ["_INTERNAL", "role::reader"]


def test_missing_acl_defaults_to_no_access():
    assert resolve_acl_tokens_from_metadata({}) == ["_NO_ACCESS"]


def test_missing_acl_can_keep_internal_for_explicit_legacy_compatibility():
    assert resolve_acl_tokens_from_metadata({}, missing_policy="internal") == ["_INTERNAL"]


def test_missing_acl_can_fail_closed_to_no_access():
    assert resolve_acl_tokens_from_metadata({}, missing_policy="no_access") == ["_NO_ACCESS"]


def test_missing_acl_can_raise_error():
    try:
        resolve_acl_tokens_from_metadata({}, missing_policy="error")
    except ValueError as exc:
        assert "acl_tokens_json" in str(exc)
    else:
        raise AssertionError("missing ACL should raise in error policy")


def test_invalid_acl_json_obeys_no_access_policy():
    tokens = resolve_acl_tokens_from_metadata({"acl_tokens_json": "not-json"}, missing_policy="no_access")

    assert tokens == ["_NO_ACCESS"]


if __name__ == "__main__":
    test_valid_acl_tokens_json_is_used_first()
    test_legacy_acl_tokens_string_is_supported()
    test_missing_acl_defaults_to_no_access()
    test_missing_acl_can_keep_internal_for_explicit_legacy_compatibility()
    test_missing_acl_can_fail_closed_to_no_access()
    test_missing_acl_can_raise_error()
    test_invalid_acl_json_obeys_no_access_policy()
    print("PASS acl payload policy tests")
