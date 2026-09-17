import ast
import os
from pathlib import Path
from typing import Optional


ROOT = Path(__file__).resolve().parents[1]


def load_filter_function():
    path = ROOT / "main.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == "_readable_qa_source_index_filter":
            module = ast.Module(body=[node], type_ignores=[])
            ast.fix_missing_locations(module)
            namespace = {"Optional": Optional}
            exec(compile(module, str(path), "exec"), namespace)
            return namespace[node.name]
    raise AssertionError("_readable_qa_source_index_filter not found")


def load_qa_acl_filter_function():
    path = ROOT / "main.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == "_qa_acl_filter":
            module = ast.Module(body=[node], type_ignores=[])
            ast.fix_missing_locations(module)
            namespace = {"Optional": Optional, "List": list}
            exec(compile(module, str(path), "exec"), namespace)
            return namespace[node.name]
    raise AssertionError("_qa_acl_filter not found")


def load_qa_knn_candidates_function():
    path = ROOT / "main.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == "_qa_knn_num_candidates":
            module = ast.Module(body=[node], type_ignores=[])
            ast.fix_missing_locations(module)
            namespace = {"os": os, "int": int, "str": str, "max": max, "print": print, "ValueError": ValueError}
            exec(compile(module, str(path), "exec"), namespace)
            return namespace[node.name]
    raise AssertionError("_qa_knn_num_candidates not found")


def load_main_tree():
    path = ROOT / "main.py"
    return ast.parse(path.read_text(encoding="utf-8"))


def test_filter_uses_source_index_and_keyword_variant():
    func = load_filter_function()

    result = func("kb_document_official,kb_document_notice")
    should = result["bool"]["should"]

    assert {"terms": {"source_index": ["kb_document_official", "kb_document_notice"]}} in should
    assert {"terms": {"source_index.keyword": ["kb_document_official", "kb_document_notice"]}} in should
    assert result["bool"]["minimum_should_match"] == 1


def test_filter_ignores_alias_or_wildcard_to_avoid_guessing():
    func = load_filter_function()

    assert func("kb_document") is None
    assert func("kb_document_*") is None


def test_filter_ignores_non_document_indexes():
    func = load_filter_function()

    assert func("kb_doc_search_v1,kb_qa_pairs") is None


def test_qa_acl_filter_matches_keyword_variant_for_dynamic_mapping():
    func = load_qa_acl_filter_function()

    result = func(["_INTERNAL"])
    should = result["bool"]["should"]

    assert {"terms": {"acl_tokens": ["_INTERNAL"]}} in should
    assert {"terms": {"acl_tokens.keyword": ["_INTERNAL"]}} in should
    assert {"bool": {"must_not": {"exists": {"field": "acl_tokens"}}}} in should
    assert result["bool"]["minimum_should_match"] == 1


def test_qa_acl_filter_keeps_super_admin_bypass():
    func = load_qa_acl_filter_function()

    assert func(["_SUPER_ADMIN"]) == {"match_all": {}}


def test_qa_acl_filter_is_fail_closed_when_tokens_missing():
    func = load_qa_acl_filter_function()

    assert func(None) == {"bool": {"must_not": {"exists": {"field": "acl_tokens"}}}}


def test_qa_response_source_projection_includes_permission_fields():
    tree = load_main_tree()
    constants = [
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant) and isinstance(node.value, str)
    ]

    assert "source_index" in constants
    assert "index_code" in constants
    assert "owner_unit_code" in constants
    assert "visible_unit_codes" in constants


def test_qa_knn_candidates_uses_safe_default_and_topk_floor():
    os.environ.pop("QA_KNN_NUM_CANDIDATES", None)
    func = load_qa_knn_candidates_function()

    assert func(3) == 100
    assert func(80) == 160


def test_qa_knn_candidates_accepts_operational_override():
    os.environ["QA_KNN_NUM_CANDIDATES"] = "240"
    try:
        func = load_qa_knn_candidates_function()

        assert func(3) == 240
    finally:
        os.environ.pop("QA_KNN_NUM_CANDIDATES", None)


def test_qa_knn_candidates_falls_back_for_invalid_env():
    os.environ["QA_KNN_NUM_CANDIDATES"] = "abc"
    try:
        func = load_qa_knn_candidates_function()

        assert func(3) == 100
    finally:
        os.environ.pop("QA_KNN_NUM_CANDIDATES", None)


def test_qa_knn_query_uses_candidate_helper_not_literal_50():
    tree = load_main_tree()
    qa_search = next(
        node for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "qa_search"
    )
    calls = [
        node for node in ast.walk(qa_search)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "_qa_knn_num_candidates"
    ]
    constants = [
        node.value
        for node in ast.walk(qa_search)
        if isinstance(node, ast.Constant)
    ]

    assert calls
    assert 50 not in constants


def test_qa_paths_reuse_shared_acl_filter():
    tree = load_main_tree()
    for function_name in ("qa_search", "qa_search_bm25"):
        function = next(
            node for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name == function_name
        )
        calls = [
            node for node in ast.walk(function)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id == "_qa_acl_filter"
        ]
        assert calls, function_name


def test_qa_paths_use_read_alias_instead_of_wildcard():
    tree = load_main_tree()
    for function_name in ("qa_search", "qa_search_bm25"):
        function = next(
            node for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name == function_name
        )
        constants = [
            node.value
            for node in ast.walk(function)
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
        ]
        imported_alias = any(
            isinstance(node, ast.ImportFrom)
            and node.module == "core.indexing.es_setup"
            and any(alias.name == "QA_INDEX_READ_ALIAS" for alias in node.names)
            for node in ast.walk(function)
        )

        assert imported_alias, function_name
        assert "kb_qa_*" not in constants


if __name__ == "__main__":
    tests = [
        test_filter_uses_source_index_and_keyword_variant,
        test_filter_ignores_alias_or_wildcard_to_avoid_guessing,
        test_filter_ignores_non_document_indexes,
        test_qa_acl_filter_matches_keyword_variant_for_dynamic_mapping,
        test_qa_acl_filter_keeps_super_admin_bypass,
        test_qa_acl_filter_is_fail_closed_when_tokens_missing,
        test_qa_response_source_projection_includes_permission_fields,
        test_qa_knn_candidates_uses_safe_default_and_topk_floor,
        test_qa_knn_candidates_accepts_operational_override,
        test_qa_knn_candidates_falls_back_for_invalid_env,
        test_qa_knn_query_uses_candidate_helper_not_literal_50,
        test_qa_paths_reuse_shared_acl_filter,
        test_qa_paths_use_read_alias_instead_of_wildcard,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
