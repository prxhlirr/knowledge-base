# -*- coding: utf-8 -*-
import json, urllib.request
ES = "http://localhost:9200"
OUT = "scratch/diag_out3.txt"; L=[]
def P(*a): L.append(" ".join(str(x) for x in a))
def post(path, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(
        ES+path, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type":"application/json"}), timeout=30))
def get(path):
    return json.load(urllib.request.urlopen(ES+path, timeout=30))

for src in ["稿.docx", "test.doc"]:
    r = post("/kb_document/_search", {"size":0,
        "query":{"bool":{"filter":[{"term":{"metadata.source":src}}]}},
        "aggs":{"latest":{"terms":{"field":"metadata.is_latest"}}}})
    bk = r["aggregations"]["latest"]["buckets"]
    # doc-level is_latest
    dl = post("/kb_doc_search_v1/_search", {"size":1,
        "query":{"term":{"source":src}}, "_source":["is_latest","doc_version","source"]})
    h = dl["hits"]["hits"][0]["_source"] if dl["hits"]["hits"] else {}
    P(repr(src))
    P("   chunk is_latest 分布:", bk)
    P("   doc-level is_latest =", h.get("is_latest"), " doc_version =", h.get("doc_version"))
    P("")

with open(OUT,"w",encoding="utf-8") as f: f.write("\n".join(L))
print("written", OUT)
