# -*- coding: utf-8 -*-
"""
清理 kb_doc_outbox 中的孤儿 WAITING 记录。

背景：full_hash 列补建前(历史窗口)上传的文档，/doc/registry 回调抛 SQL 异常，
outbox 永久停在 WAITING(doc_version=0)。这些文档大多已被后续成功上传激活
(ES 中存在 is_latest=true 的 chunk)，遗留的 WAITING 记录是无害但混淆的孤儿数据。

安全策略：
  - 仅处理 status='WAITING' 的记录；
  - 对每条，检查其 source_name 在 ES(kb_document) 中是否已有 is_latest=true 的 chunk：
      * 有  -> 视为孤儿，安全关闭(WAITING -> DONE，附 error_msg 说明)；
      * 无  -> 视为真卡死，【不处理】并报告(需另行激活)。
  - 默认 DRY-RUN；加 --apply 才真正 UPDATE。
"""
import sys, json, urllib.request, psycopg2

ES = "http://localhost:9200"
PG = dict(host="localhost", port=5432, dbname="knowledge_base",
          user="postgres", password="liyz", connect_timeout=5)
APPLY = "--apply" in sys.argv


def es_has_latest(source):
    r = json.load(urllib.request.urlopen(urllib.request.Request(
        ES + "/kb_document/_count",
        data=json.dumps({"query": {"bool": {"filter": [
            {"term": {"metadata.source": source}},
            {"term": {"metadata.is_latest": True}}]}}}).encode(),
        headers={"Content-Type": "application/json"}, method="POST"), timeout=30))
    return r.get("count", 0)


conn = psycopg2.connect(**PG); cur = conn.cursor()
cur.execute("SELECT id, source_name, doc_version, created_at FROM kb_doc_outbox WHERE status='WAITING' ORDER BY id")
rows = cur.fetchall()

print("=" * 66)
print("模式:", "APPLY（将真实写入）" if APPLY else "DRY-RUN（只读，不写入）")
print("=" * 66)
print("WAITING 记录总数:", len(rows))

orphans, stuck = [], []
for oid, src, ver, ts in rows:
    if es_has_latest(src) > 0:
        orphans.append((oid, src, ver, ts))
    else:
        stuck.append((oid, src, ver, ts))

print("\n--- 孤儿(文档已激活，可安全关闭) %d 条 ---" % len(orphans))
for oid, src, ver, ts in orphans:
    print("  id=%-4d %-32s v=%s  %s" % (oid, repr(src), ver, ts))
    if APPLY:
        cur.execute(
            "UPDATE kb_doc_outbox SET status='DONE', "
            "error_msg=COALESCE(error_msg,'')||' | orphan-cleanup: doc already activated (callback failed pre-full_hash-column)' "
            "WHERE id=%s", (oid,))

print("\n--- 真卡死(ES 无 is_latest=true chunk，不处理) %d 条 ---" % len(stuck))
for oid, src, ver, ts in stuck:
    print("  id=%-4d %-32s v=%s  %s  <- 需另行激活" % (oid, src, ver, ts))

if APPLY:
    conn.commit()
    print("\n已提交：关闭孤儿 WAITING %d 条。" % len(orphans))
else:
    print("\n(dry-run；加 --apply 真正写入。真卡死的 %d 条不会被处理。)" % len(stuck))
conn.close()
