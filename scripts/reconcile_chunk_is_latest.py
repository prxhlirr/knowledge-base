# -*- coding: utf-8 -*-
"""
对账修复：将"doc-level/registry 视为最新、但 chunk 全为 is_latest=false"的卡死文档
的最新版本 chunk 激活为 is_latest=true（与 OutboxPoller 激活语义一致）。

安全策略：
  - 仅处理「该 source 下 is_latest=true 的 chunk 数 == 0 且总 chunk > 0」的文档
    （= 真卡死；若存在更新的 is_latest=true 版本，说明是被取代，不动）。
  - 仅激活该 source 下 doc_version 最大的那批 chunk。
  - 默认 dry-run，仅打印；加 --apply 才真正 update_by_query。
"""
import json, sys, urllib.request

ES = "http://localhost:9200"
INDEX = "kb_document"  # 别名，覆盖全部 kb_document_* 物理索引
APPLY = "--apply" in sys.argv


def post(path, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(
        ES + path, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json"}), timeout=60))


def tot(r):
    t = r["hits"]["total"]
    return t["value"] if isinstance(t, dict) else t


# 1) 找出所有 source 的 is_latest 分布
agg = post("/%s/_search" % INDEX, {
    "size": 0,
    "query": {"bool": {"must_not": [{"term": {"metadata.is_latest": True}}]}},
    "aggs": {
        "by_source": {
            "terms": {"field": "metadata.source", "size": 1000},
            "aggs": {
                "ver": {"terms": {"field": "metadata.doc_version", "size": 50}}
            }
        }
    }
})

# 每个 source 的总 chunk 数（含 true/false）
allsrc = post("/%s/_search" % INDEX, {
    "size": 0,
    "aggs": {"by_source": {"terms": {"field": "metadata.source", "size": 1000}}}})
total_by_src = {b["key"]: b["doc_count"]
                for b in allsrc["aggregations"]["by_source"]["buckets"]}

stuck = []
for b in agg["aggregations"]["by_source"]["buckets"]:
    src = b["key"]
    false_cnt = b["doc_count"]
    total = total_by_src.get(src, false_cnt)
    true_cnt = total - false_cnt
    if true_cnt == 0 and total > 0:  # 真卡死：没有任何 is_latest=true chunk
        versions = sorted(b["ver"]["buckets"],
                          key=lambda x: int(x["key"]) if str(x["key"]).isdigit() else 0)
        max_ver = versions[-1]["key"] if versions else None
        stuck.append((src, total, false_cnt, max_ver))

print("=" * 66)
print("模式:", "APPLY（将真实写入）" if APPLY else "DRY-RUN（只读，不写入）")
print("=" * 66)
print("发现卡死文档(is_latest 全 false)数:", len(stuck))
for src, total, false_cnt, mv in stuck:
    print("  - %-40s total=%d false=%d 激活版本=%s"
          % (repr(src), total, false_cnt, mv))

if not stuck:
    print("\n无卡死文档，无需修复。")
    sys.exit(0)

# 2) 逐个激活其最大版本 chunk
print("\n" + ("执行激活..." if APPLY else "拟执行(dry-run)以下 update_by_query:"))
activated = 0
for src, total, false_cnt, mv in stuck:
    q = {"bool": {
        "filter": [
            {"term": {"metadata.source": src}},
            {"term": {"metadata.doc_version": mv}},
        ]}}
    body = {
        "query": q,
        "script": {"source": "ctx._source.metadata.is_latest = true",
                   "lang": "painless"},
        "conflicts": "proceed",
    }
    if APPLY:
        r = post("/%s/_update_by_query?refresh=true&conflicts=proceed" % INDEX, body)
        print("  [APPLY] %-40s v=%s updated=%s"
              % (repr(src), mv, r.get("updated")))
        activated += r.get("updated", 0)
    else:
        cnt = post("/%s/_count" % INDEX, {"query": q}).get("count", 0)
        print("  [DRY ] %-40s v=%s 将激活 chunk 数=%s"
              % (repr(src), mv, cnt))

print("\n完成。", "共激活 chunk 数=%d" % activated if APPLY else "(dry-run，加 --apply 真正写入)")
