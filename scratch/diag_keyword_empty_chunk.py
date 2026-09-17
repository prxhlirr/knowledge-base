# -*- coding: utf-8 -*-
"""确认 /search/home 关键词检索 chunk_text 为空根因。结果写 UTF-8 文件。"""
import json, urllib.parse, urllib.request

ES = "http://localhost:9200"
TERM = "市场"
OUT = "scratch/diag_out.txt"
_lines = []
def P(*a): _lines.append(" ".join(str(x) for x in a))

def http(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(ES + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

def total_of(res):
    t = res["hits"]["total"]
    return t["value"] if isinstance(t, dict) else t

# ---- A: doc-level 命中 ----
P("=" * 70)
P("【A】kb_doc_search_v1 命中「%s」的 doc-level 文档" % TERM)
P("=" * 70)
ds = http("GET", "/kb_doc_search_v1/_search?size=50&_source=source,source_name,title,"
          "content,summary,representative_chunk_ids,chunk_count&q=" +
          urllib.parse.quote(TERM))
ds_hits = ds["hits"]["hits"]
P("命中 doc-level 文档数:", len(ds_hits))
doc_sources = []
for h in ds_hits:
    s = h["_source"]; src = s.get("source") or s.get("source_name") or ""
    doc_sources.append(src)
    P("  _id:", h["_id"][:20])
    P("    source           =", repr(src))
    P("    title            =", repr((s.get("title") or "")[:50]))
    P("    content 长度     =", len(s.get("content") or ""), "(空)" if not (s.get("content") or "") else "(非空)")
    P("    representative   =", s.get("representative_chunk_ids"))
    P("    chunk_count      =", s.get("chunk_count"))
    P("")

# ---- B: chunk 索引里 source 的精确/前缀/包含关联 ----
P("=" * 70)
P("【B】kb_document 中按 source 关联核查（精确 / 通配）")
P("=" * 70)
for src in doc_sources:
    if not src: continue
    # B1 精确 term
    b1 = http("POST", "/kb_document/_search", {"size": 0,
            "query": {"term": {"metadata.source": src}}})
    exact = total_of(b1)
    # B2 含「市场」的 chunk（同 source）
    b2 = http("POST", "/kb_document/_search", {"size": 0,
            "query": {"bool": {"filter": [{"term": {"metadata.source": src}}],
                               "must": [{"match": {"content": TERM}}]}}})
    with_term = total_of(b2)
    # B3 通配 metadata.source（看是否有 sanitize 后的近似名）
    b3 = http("POST", "/kb_document/_search", {"size": 0,
            "query": {"wildcard": {"metadata.source": {"value": "*%s*" % src.replace(".","?")}}}})
    wild = total_of(b3)
    # B4 该 source 下 chunk 的 metadata.source 真实取值（取样）
    b4 = http("POST", "/kb_document/_search", {"size": 1,
            "query": {"wildcard": {"metadata.source": {"value": "*%s*" % src.replace(".","?")}}},
            "aggs": {"srcs": {"terms": {"field": "metadata.source", "size": 5}}},
            "_source": ["metadata.source", "chunk_granularity"]})
    real_srcs = [b["key"] for b in b4.get("aggregations", {}).get("srcs", {}).get("buckets", [])]
    P("  doc-level source =", repr(src))
    P("    精确 term 命中 chunk 数    =", exact,
      "  [含「%s」=%d]" % (TERM, with_term))
    P("    通配 *%s* 命中 chunk 数    =" % src, wild)
    P("    chunk 里真实 metadata.source 取值 =", real_srcs)
    P("    >>>", "零 chunk 关联 → chunk_text 必空 (Case 2: source 关联失败)"
       if exact == 0 else ("有 chunk 但精确名不匹配(疑似 sanitize 差异)" if exact == 0 and wild > 0
                           else "有 chunk 可关联"))
    P("")

# ---- C: kb_document 直接按正文「市场」命中的 source 集合 ----
P("=" * 70)
P("【C】kb_document 正文含「%s」的真实文档(source)集合" % TERM)
P("=" * 70)
c = http("POST", "/kb_document/_search", {"size": 0,
        "query": {"match": {"content": TERM}},
        "aggs": {"srcs": {"terms": {"field": "metadata.source", "size": 30}}}})
buckets = c.get("aggregations", {}).get("srcs", {}).get("buckets", [])
P("正文含「%s」的文档数(distinct source): %d" % (TERM, len(buckets)))
for b in buckets:
    P("   -", repr(b["key"]), "chunks=", b["doc_count"])
P("")
P("【对照】doc-level 命中的 source:", [repr(s) for s in doc_sources])
P("       正文侧命中的 source:", [repr(b["key"]) for b in buckets])
overlap = set(doc_sources) & set(b["key"] for b in buckets)
P("       交集:", [repr(s) for s in overlap])

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(_lines))
print("written", OUT)
