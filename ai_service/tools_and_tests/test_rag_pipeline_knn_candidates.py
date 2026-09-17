import ast
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAG_PIPELINE = ROOT / "core" / "rag_pipeline.py"


def load_rag_pipeline_tree():
    return ast.parse(RAG_PIPELINE.read_text(encoding="utf-8"))


def load_local_search_candidates_function():
    tree = load_rag_pipeline_tree()
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == "local_search_knn_num_candidates":
            module = ast.Module(body=[node], type_ignores=[])
            ast.fix_missing_locations(module)
            namespace = {"os": os, "int": int, "str": str, "max": max, "print": print, "ValueError": ValueError}
            exec(compile(module, str(RAG_PIPELINE), "exec"), namespace)
            return namespace[node.name]
    raise AssertionError("local_search_knn_num_candidates not found")


def test_local_search_candidates_uses_safe_default_and_topk_floor():
    os.environ.pop("RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES", None)
    func = load_local_search_candidates_function()

    assert func(5) == 200
    assert func(150) == 300


def test_local_search_candidates_accepts_operational_override():
    os.environ["RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES"] = "480"
    try:
        func = load_local_search_candidates_function()

        assert func(5) == 480
    finally:
        os.environ.pop("RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES", None)


def test_local_search_candidates_falls_back_for_invalid_env():
    os.environ["RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES"] = "bad"
    try:
        func = load_local_search_candidates_function()

        assert func(5) == 200
    finally:
        os.environ.pop("RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES", None)


def test_rag_pipeline_search_uses_candidate_helper_not_literal_100():
    tree = load_rag_pipeline_tree()
    search_method = None
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef) and node.name == "search":
            search_method = node
            break
    if search_method is None:
        raise AssertionError("RAGPipeline.search not found")

    calls = [
        node for node in ast.walk(search_method)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "local_search_knn_num_candidates"
    ]
    constants = [
        node.value
        for node in ast.walk(search_method)
        if isinstance(node, ast.Constant)
    ]

    assert calls
    assert 100 not in constants


if __name__ == "__main__":
    tests = [
        test_local_search_candidates_uses_safe_default_and_topk_floor,
        test_local_search_candidates_accepts_operational_override,
        test_local_search_candidates_falls_back_for_invalid_env,
        test_rag_pipeline_search_uses_candidate_helper_not_literal_100,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
