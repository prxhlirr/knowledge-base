import ast
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAG_PIPELINE = ROOT / "core" / "rag_pipeline.py"


def load_rag_tree():
    return ast.parse(RAG_PIPELINE.read_text(encoding="utf-8"))


def find_method(tree: ast.AST, class_name: str, method_name: str) -> ast.FunctionDef:
    for node in tree.body:
        if isinstance(node, ast.ClassDef) and node.name == class_name:
            for item in node.body:
                if isinstance(item, ast.FunctionDef) and item.name == method_name:
                    return item
    raise AssertionError(f"{class_name}.{method_name} not found")


def string_constants(node: ast.AST) -> set[str]:
    return {
        item.value
        for item in ast.walk(node)
        if isinstance(item, ast.Constant) and isinstance(item.value, str)
    }


def test_qa_bulk_write_uses_write_alias():
    tree = load_rag_tree()
    method = find_method(tree, "RAGPipeline", "_generate_and_index_qa_pairs")
    index_values = []
    for node in ast.walk(method):
        if isinstance(node, ast.Dict):
            for key, value in zip(node.keys, node.values):
                if isinstance(key, ast.Constant) and key.value == "_index":
                    index_values.append(value)

    assert any(isinstance(value, ast.Name) and value.id == "QA_INDEX_WRITE_ALIAS" for value in index_values)


def test_qa_bulk_write_projects_permission_fields():
    tree = load_rag_tree()
    method = find_method(tree, "RAGPipeline", "_generate_and_index_qa_pairs")
    constants = string_constants(method)

    for field in (
        "source_index",
        "index_code",
        "owner_unit_code",
        "visible_unit_codes",
        "permission_version",
        "acl_tokens",
    ):
        assert field in constants


def test_qa_generation_uses_permission_projection_once():
    tree = load_rag_tree()
    method = find_method(tree, "RAGPipeline", "_generate_and_index_qa_pairs")
    calls = [
        node
        for node in ast.walk(method)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "_qa_permission_projection"
    ]

    assert len(calls) == 1


def test_qa_queue_payload_carries_permission_fields():
    tree = load_rag_tree()
    process_method = find_method(tree, "RAGPipeline", "process_and_index")
    constants = string_constants(process_method)

    for field in (
        "targetIndex",
        "ownerUnitCode",
        "visibleUnitCodes",
        "permissionVersion",
        "acl_tokens",
    ):
        assert field in constants


if __name__ == "__main__":
    tests = [
        test_qa_bulk_write_uses_write_alias,
        test_qa_bulk_write_projects_permission_fields,
        test_qa_generation_uses_permission_projection_once,
        test_qa_queue_payload_carries_permission_fields,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
