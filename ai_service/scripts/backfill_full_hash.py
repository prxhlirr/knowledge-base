# -*- coding: utf-8 -*-
"""
回填 kb_doc_registry.full_hash（历史 NULL 行）。

背景：旧 AI 镜像不透传 full_hash，导致 registry.full_hash 全空，existsByFullHash 去重失效。
本脚本对 is_latest=1 且 full_hash 为 NULL 的行，按 storage_path 读取本地文件计算全文件 SHA-256 并回填。
（仅处理本地可读路径；MinIO object key 跳过并报告——那些需 MinIO 客户端另处理。）

默认 DRY-RUN；加 --apply 才真正 UPDATE。
"""
import os, sys, hashlib, psycopg2

PG = dict(host="localhost", port=5432, dbname="knowledge_base",
          user="postgres", password="liyz", connect_timeout=5)
APPLY = "--apply" in sys.argv


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


conn = psycopg2.connect(**PG); cur = conn.cursor()
cur.execute("""SELECT id, source_name, storage_path FROM kb_doc_registry
               WHERE full_hash IS NULL AND is_latest = 1 ORDER BY id""")
rows = cur.fetchall()

print("=" * 66)
print("模式:", "APPLY（写入）" if APPLY else "DRY-RUN（只读）")
print("=" * 66)
print("is_latest=1 且 full_hash NULL 行数:", len(rows))

ok, skip = [], []
for rid, src, sp in rows:
    if sp and os.path.isfile(sp):
        try:
            h = sha256_file(sp)
            ok.append((rid, src, sp, h))
        except Exception as e:
            skip.append((rid, src, sp, "读失败:%s" % e))
    else:
        skip.append((rid, src, sp, "本地文件不存在(可能是 MinIO key 或已删除)"))

print("\n--- 可回填(本地文件可读) %d 条 ---" % len(ok))
for rid, src, sp, h in ok:
    print("  id=%-4d %-28s -> %s" % (rid, repr(src)[:28], h[:16] + "..."))
    if APPLY:
        cur.execute("UPDATE kb_doc_registry SET full_hash=%s WHERE id=%s", (h, rid))

print("\n--- 跳过 %d 条 ---" % len(skip))
for rid, src, sp, why in skip:
    print("  id=%-4d %-28s %s" % (rid, repr(src)[:28], why))

if APPLY:
    conn.commit(); print("\n已提交：回填 %d 条。" % len(ok))
else:
    print("\n(dry-run；加 --apply 写入。跳过项需另行处理。)")
conn.close()
