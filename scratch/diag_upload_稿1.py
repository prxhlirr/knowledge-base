# -*- coding: utf-8 -*-
"""定位 稿1.docx 上传后卡在哪一阶段：ES / Redis / MySQL 全链路体检。"""
import json, urllib.request, urllib.parse
ES = "http://localhost:9200"
REDIS_HOST, REDIS_PORT = "localhost", 6379
OUT = "scratch/diag_upload.txt"; L = []
def P(*a): L.append(" ".join(str(x) for x in a))
def es(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    return json.load(urllib.request.urlopen(urllib.request.Request(
        ES+path, data=data, method=method,
        headers={"Content-Type":"application/json"}), timeout=30))
def es_total(r):
    t = r["hits"]["total"]; return t["value"] if isinstance(t, dict) else t

KEY = "稿1"   # 用关键字匹配，兼容 sanitize/版本后缀

P("="*70); P("【Stage 4/6】ES: 稿1.docx 的 chunk 是否存在(kb_document, 任意 is_latest)"); P("="*70)
r = es("POST","/kb_document/_search", {"size":0,
    "query":{"wildcard":{"metadata.source":"*%s*"%KEY}},
    "aggs":{"by_src":{"terms":{"field":"metadata.source","size":10}},
            "latest":{"terms":{"field":"metadata.is_latest"}}}})
P("  kb_document 命中 chunk 总数:", es_total(r))
for b in r["aggregations"]["by_src"]["buckets"]:
    P("    source =", repr(b["key"]), "count =", b["doc_count"])
P("  is_latest 分布:", r["aggregations"]["latest"]["buckets"])
# 看一条样本
smp = es("POST","/kb_document/_search", {"size":1,
    "query":{"wildcard":{"metadata.source":"*%s*"%KEY}},
    "_source":["metadata","chunk_granularity","content"],"sort":[{"metadata.doc_version":"desc"}]})
if smp["hits"]["hits"]:
    h = smp["hits"]["hits"][0]; m = h["_source"].get("metadata",{}) or {}
    P("  样本 chunk _id:", h["_id"])
    P("    metadata.source =", repr(m.get("source")), "| doc_version =", m.get("doc_version"),
      "| is_latest =", m.get("is_latest"), "| gran =", h["_source"].get("chunk_granularity"),
      "| content len =", len(h["_source"].get("content") or ""))
else:
    P("  （无任何 chunk 命中）")
P("")

P("="*70); P("【doc-level】kb_doc_search / kb_qa_pairs 是否有 稿1"); P("="*70)
for idx, field in [("kb_doc_search","source"), ("kb_qa_pairs","source")]:
    try:
        r = es("POST","/%s/_search"%idx, {"size":0,"query":{"wildcard":{field:"*%s*"%KEY}}})
        P("  %-14s 命中: %d"%(idx, es_total(r)))
    except Exception as e:
        P("  %-14s 查询异常: %s"%(idx, e))
P("")

P("="*70); P("【Stage 3】Redis 队列里是否还有 稿1 待消费任务"); P("="*70)
try:
    import redis
    rc = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    rc.ping()
    for q in ["DOC_TASK_QUEUE_HIGH","DOC_TASK_QUEUE","DOC_TASK_DLQ"]:
        n = rc.llen(q)
        hit = []
        if n:
            for raw in rc.lrange(q, 0, min(n-1, 499)):
                if KEY in raw: hit.append(raw[:160])
        P("  %-18s len=%d  含稿1的任务数=%d"%(q, n, len(hit)))
        for h in hit[:3]: P("      ->", h)
except Exception as e:
    P("  Redis 查询失败:", e)
P("")

P("="*70); P("【Stage 3/5/6】MySQL: task / outbox / registry 状态"); P("="*70)
try:
    import psycopg2
    conn = psycopg2.connect(host="localhost", port=5432, dbname="knowledge_base",
                            user="postgres", password="liyz", connect_timeout=5)
    cur = conn.cursor()
    for tbl, cols, where in [
        ("sys_doc_import_task", "task_id,status,original_name,created_at", "original_name LIKE '%稿1%'"),
        ("kb_doc_outbox", "id,task_id,source_name,status,doc_version,retry_count,created_at", "source_name LIKE '%稿1%'"),
        ("kb_doc_registry", "id,source_name,doc_version,is_latest,status,full_hash,chunk_count", "source_name LIKE '%稿1%'"),
    ]:
        try:
            cur.execute("SELECT %s FROM %s WHERE %s ORDER BY 1"%(cols, tbl, where))
            rows = cur.fetchall()
            P("  %s: %d 行"%(tbl, len(rows)))
            for row in rows:
                P("      ", row)
        except Exception as e:
            P("  %s 查询异常: %s"%(tbl, e)); conn.rollback()
    # 最近的批次
    try:
        cur.execute("SELECT batch_id, ingest_mode, total_count, success_count, error_count, status FROM sys_doc_batch ORDER BY created_at DESC LIMIT 5")
        P("  最近 sys_doc_batch:")
        for row in cur.fetchall(): P("      ", row)
    except Exception as e:
        P("  sys_doc_batch 异常:", e); conn.rollback()
    conn.close()
except Exception as e:
    P("  PostgreSQL 连接失败:", e)
P("")

with open(OUT,"w",encoding="utf-8") as f: f.write("\n".join(L))
print("written", OUT)
