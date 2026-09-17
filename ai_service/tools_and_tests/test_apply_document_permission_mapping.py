import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_script():
    path = ROOT / "scripts" / "apply_document_permission_mapping.py"
    spec = importlib.util.spec_from_file_location("apply_document_permission_mapping", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeIndices:
    def __init__(self, mappings):
        self.mappings = mappings
        self.applied = []

    def get(self, index, expand_wildcards="open"):
        return {name: {} for name in self.mappings.keys()}

    def get_mapping(self, index):
        return {
            index: {
                "mappings": {
                    "properties": self.mappings[index],
                }
            }
        }

    def put_mapping(self, index, properties):
        self.applied.append((index, properties))


class FakeEs:
    def __init__(self, mappings):
        self.indices = FakeIndices(mappings)


def test_build_plan_adds_missing_permission_fields():
    mod = load_script()

    plan = mod.build_plan("kb_document_official", {"content": {"type": "text"}})

    assert set(plan.missing.keys()) == set(mod.PERMISSION_MAPPING.keys())
    assert not plan.conflicts
    assert plan.safe_to_apply


def test_build_plan_detects_type_conflict():
    mod = load_script()

    plan = mod.build_plan(
        "kb_document_public",
        {
            "source_index": {"type": "text"},
            "index_code": {"type": "keyword"},
        },
    )

    assert plan.conflicts["source_index"] == ("text", "keyword")
    assert plan.existing["index_code"] == "keyword"
    assert not plan.safe_to_apply


def test_build_plans_only_uses_kb_document_indices():
    mod = load_script()
    es = FakeEs(
        {
            "kb_document_official": {},
            "kb_doc_search": {},
            "kb_document_public": {},
        }
    )

    plans = mod.build_plans(es, "kb_document_*")

    assert [plan.index for plan in plans] == ["kb_document_official", "kb_document_public"]


def test_apply_plans_skips_dry_run_and_conflicts():
    mod = load_script()
    es = FakeEs({"kb_document_official": {}})
    safe = mod.build_plan("kb_document_official", {})
    conflict = mod.build_plan("kb_document_public", {"source_index": {"type": "text"}})

    mod.apply_plans(es, [safe, conflict], execute=False)

    assert es.indices.applied == []
    assert not safe.applied
    assert not conflict.applied


def test_apply_plans_puts_only_missing_fields():
    mod = load_script()
    es = FakeEs({"kb_document_official": {}})
    plan = mod.build_plan(
        "kb_document_official",
        {
            "source_index": {"type": "keyword"},
            "index_code": {"type": "keyword"},
        },
    )

    mod.apply_plans(es, [plan], execute=True)

    assert plan.applied
    assert es.indices.applied == [
        (
            "kb_document_official",
            {
                "owner_unit_code": {"type": "keyword"},
                "visible_unit_codes": {"type": "keyword"},
                "permission_version": {"type": "long"},
            },
        )
    ]


if __name__ == "__main__":
    tests = [
        test_build_plan_adds_missing_permission_fields,
        test_build_plan_detects_type_conflict,
        test_build_plans_only_uses_kb_document_indices,
        test_apply_plans_skips_dry_run_and_conflicts,
        test_apply_plans_puts_only_missing_fields,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
