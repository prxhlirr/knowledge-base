import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_script(name):
    path = ROOT / "scripts" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_doc_search_backfill_normalizes_source_fields():
    mod = load_script("backfill_kb_doc_search")
    src = {
            "metadata": {
                "source": "%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C.docx",
                "title": "%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C",
                "document_number": "%E7%94%98%E6%A3%80%E5%AD%972026",
            },
            "content": "%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C",
        }
    group = mod.new_group(src, "hit-1")
    mod.add_chunk(group, src)

    _, body = mod.build_doc_body(group)

    assert body["source"] == "检验检测工作.docx"
    assert body["source_name"] == "检验检测工作.docx"
    assert body["title"] == "检验检测工作"
    assert body["document_number"] == "甘检字2026"
    assert "%E6" not in body["doc_terms"]
    assert body["summary"] == "检验检测工作"


def test_doc_meta_backfill_normalizes_source_fields():
    mod = load_script("backfill_doc_meta_v2")
    group = {
        "source": "%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C.docx",
        "title": "%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C",
        "summary": "%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C",
        "count": 1,
        "sum": [0.0] * mod.VECTOR_DIM,
    }

    body = mod.build_doc_meta_body(group)

    assert body["source"] == "检验检测工作.docx"
    assert body["source_name"] == "检验检测工作.docx"
    assert body["title"] == "检验检测工作"
    assert body["summary"] == "检验检测工作"
