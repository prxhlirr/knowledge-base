# -*- coding: utf-8 -*-
"""检查 doc-level doc_id 与 chunk metadata.doc_id 是否对齐（hash 分支能否命中）"""
import json, urllib.request
ES = "http://localhost:9200"
OUT = "scratch/diag_out2.txt"
L = []
def P(*a): L.append(" ".join(str(x) for x in a))
def http(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(ES+path, data=data, method=method,
                                 headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req, timeout=30) as r: return json.load(r)

for src in ["稿.docx", "test.doc"]:
    P("="*70); P("source =", repr(src)); P("="*70)
    # doc-level 记录
    dl = http("POST","/kb_doc_search_v1/_search", {"size":1,
        "query":{"term":{"source":src}},
        "_source":["doc_id","source","title","content_hash","content"]})
    if dl["hits"]["hits"]:
        s = dl["hits"]["hits"][0]["_source"]
        P("[doc-level kb_doc_search]")
        P("   _id          =", dl["hits"]["hits"][0]["_id"][:24], "...")
        P("   doc_id       =", repr(s.get("doc_id")))
        P("   content_hash =", repr(s.get("content_hash")))
        P("   source       =", repr(s.get("source")))
        P("   content len  =", len(s.get("content") or ""))
    else:
        P("[doc-level] 未命中 source=", repr(src))
    P("")
    # 一个 chunk
    ck = http("POST","/kb_document/_search", {"size":2,
        "query":{"bool":{
            "filter":[{"term":{"metadata.source":src}}],
            "should":[{"match":{"content":"市场"}}]}},
        "_source":["metadata","chunk_granularity","content"]})
    P("[chunk kb_document] 命中 chunk 数:", ck["hits"]["total"]["value"]
      if isinstance(ck["hits"]["total"],dict) else ck["hits"]["total"])
    for h in ck["hits"]["hits"][:2]:
        s = h["_source"]; m = s.get("metadata",{}) or {}
        P("   chunk _id            =", h["_id"])
        P("   metadata.doc_id      =", repr(m.get("doc_id")))
        P("   metadata.source      =", repr(m.get("source")))
        P("   metadata.is_latest   =", repr(m.get("is_latest")))
        P("   chunk_granularity    =", repr(s.get("chunk_granularity")))
        P("   content len          =", len(s.get("content") or ""))
        P("   content 含「市场」?   ", "市场" in (s.get("content") or ""))
        P("")
    # 对齐判定
    P("[对齐判定]")
    if dl["hits"]["hits"]:
        dl_docid = (dl["hits"]["hits"][0]["_source"].get("doc_id") or "")
        ck_docids = set()
        for h in ck["hits"]["hits"]:
            m = h["_source"].get("metadata",{}) or {}
            if m.get("doc_id"): ck_docids.add(m["doc_id"])
        P("   doc-level.doc_id =", repr(dl_docid))
        P("   chunk.metadata.doc_id 取值 =", ck_docids or "(空/无此字段)")
        P("   >>> hash 分支(metadata.doc_id)命中?", dl_docid in ck_docids if ck_docids else False,
          "| filename 分支(metadata.source)命中: True(已验证有 chunk)")
    P("")

with open(OUT,"w",encoding="utf-8") as f: f.write("\n".join(L))
print("written", OUT)
