"""
验证脚本：用 search_audit_log 真实埋点回测首页关键词检索的耗时假设。

连接：PostgreSQL knowledge_base (application-prod.yml)
用途：只读 SELECT，不改任何数据。

运行：
  python scratch/verify_keyword_latency.py
  python scratch/verify_keyword_latency.py --days 14
"""
import argparse
import sys

import psycopg2

DB = dict(host="127.0.0.1", port=5432, dbname="knowledge_base", user="postgres", password="liyz")


def pct_sql(col):
    return ", ".join(
        f"PERCENTILE_CONT({p}) WITHIN GROUP (ORDER BY {col}) AS p{int(p*100)}"
        for p in (0.5, 0.9, 0.95, 0.99)
    )


def run(cur, title, sql, params=()):
    print("\n" + "=" * 78)
    print(title)
    print("-" * 78)
    try:
        cur.execute(sql, params)
        cols = [d[0] for d in cur.description]
        rows = cur.fetchall()
    except Exception as e:
        print(f"[QUERY ERROR] {e}")
        return
    if not rows:
        print("(no rows)")
        return
    # 友好打印：表头 + 每行
    print("\t".join(cols))
    for r in rows:
        cells = []
        for v in r:
            if isinstance(v, float):
                cells.append(f"{v:.1f}")
            else:
                cells.append(str(v) if v is not None else "")
        print("\t".join(cells))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=7, help="回看窗口（天）")
    args = ap.parse_args()
    days = args.days

    conn = psycopg2.connect(**DB)
    cur = conn.cursor()

    # Q0 — sanity：总量、模式分布、时间范围
    run(cur, f"Q0 数据概况（近 {days} 天）", f"""
        SELECT search_mode,
               COUNT(*) AS n,
               MIN(create_time) AS first_ts,
               MAX(create_time) AS last_ts
        FROM search_audit_log
        WHERE create_time > now() - interval '{days} days'
        GROUP BY search_mode
        ORDER BY n DESC;
    """)

    kw = f"search_mode='keyword' AND create_time > now() - interval '{days} days'"

    # Q1 — keyword 端到端耗时分布（headline）
    run(cur, "Q1 keyword 端到端 total_cost_ms 分布 (ms)", f"""
        SELECT COUNT(*) AS n, {pct_sql('total_cost_ms')},
               AVG(total_cost_ms)::int AS avg, MAX(total_cost_ms) AS max
        FROM search_audit_log WHERE {kw};
    """)

    # Q2 — 阶段耗时完整分解（含每步新列，残差应→~0）
    run(cur, "Q2 keyword 阶段耗时完整分解 (ms)", f"""
        SELECT
          COUNT(*) AS n,
          AVG(total_cost_ms)::int             AS avg_total,
          AVG(es_cost_ms)::int                AS avg_es_recall,
          AVG(doc_search_ms)::int             AS avg_doc_search,
          AVG(literal_recall_ms)::int         AS avg_literal,
          AVG(keyword_doc_match_ms)::int      AS avg_doc_match,
          AVG(coarse_evidence_ms)::int        AS avg_coarse,
          AVG(keyword_rank_ms)::int           AS avg_rank,
          AVG(result_assemble_ms)::int        AS avg_assemble,
          AVG(permission_filter_ms)::int      AS avg_permission,
          AVG(embedding_cost_ms)::int         AS avg_embedding,
          AVG(rerank_cost_ms)::int            AS avg_rerank,
          AVG(GREATEST(total_cost_ms
                       - COALESCE(es_cost_ms,0) - COALESCE(literal_recall_ms,0)
                       - COALESCE(keyword_doc_match_ms,0) - COALESCE(coarse_evidence_ms,0)
                       - COALESCE(keyword_rank_ms,0) - COALESCE(result_assemble_ms,0)
                       - COALESCE(permission_filter_ms,0)
                       - COALESCE(embedding_cost_ms,0) - COALESCE(rerank_cost_ms,0), 0))::int
              AS avg_residual,
          AVG(coarse_evidence_ms::float / NULLIF(total_cost_ms,0)) AS coarse_share,
          AVG(es_cost_ms::float / NULLIF(total_cost_ms,0))         AS es_recall_share
        FROM search_audit_log WHERE {kw};
    """)

    # Q3 — literal 短路是否触发（literal_hit_count >= return_top_k 才短路）
    run(cur, "Q3 literal 短路触发率（hit>=return_top_k 才短路）", f"""
        SELECT
          COUNT(*) AS n,
          SUM(CASE WHEN literal_hit_count >= return_top_k THEN 1 ELSE 0 END) AS short_circuit_rows,
          AVG((literal_hit_count >= return_top_k)::int)::float AS short_circuit_rate,
          AVG(literal_hit_count)::float AS avg_literal_hit,
          AVG(return_top_k)::float AS avg_return_top_k,
          MAX(literal_hit_count) AS max_literal_hit
        FROM search_audit_log WHERE {kw};
    """)

    # Q4 — return_top_k 分布（区分 /home=50 与 /search）
    run(cur, "Q4 按 return_top_k 分桶（50≈首页 keyword）", f"""
        SELECT return_top_k,
               COUNT(*) AS n,
               AVG(total_cost_ms)::int AS avg_total,
               PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY total_cost_ms)::int AS p95_total,
               AVG(es_cost_ms)::int AS avg_es_recall,
               AVG(bm25_hits)::int AS avg_bm25,
               AVG(doc_search_candidates)::int AS avg_doc_cand
        FROM search_audit_log WHERE {kw}
        GROUP BY return_top_k ORDER BY return_top_k;
    """)

    # Q5 — doc_search 是否启用 / 占比
    run(cur, "Q5 doc_search 双索引召回启用情况", f"""
        SELECT doc_search_enabled,
               COUNT(*) AS n,
               AVG(doc_search_ms)::int AS avg_doc_search_ms,
               AVG(doc_search_candidates)::int AS avg_candidates,
               AVG(total_cost_ms)::int AS avg_total
        FROM search_audit_log WHERE {kw}
        GROUP BY doc_search_enabled;
    """)

    # Q6 — 慢查询样本（看形态）
    run(cur, "Q6 keyword 最慢 15 条样本", f"""
        SELECT total_cost_ms, es_cost_ms, doc_search_ms,
               return_top_k, literal_hit_count, bm25_hits, doc_search_candidates,
               LEFT(query_text, 24) AS query
        FROM search_audit_log WHERE {kw}
        ORDER BY total_cost_ms DESC LIMIT 15;
    """)

    # Q7 — resolved_index 分布（确认是否打在 kb_document 1 亿别名上）
    run(cur, "Q7 keyword 命中的 resolved_index 分布", f"""
        SELECT resolved_index, COUNT(*) AS n,
               AVG(total_cost_ms)::int AS avg_total,
               AVG(es_cost_ms)::int AS avg_es_recall
        FROM search_audit_log WHERE {kw}
        GROUP BY resolved_index ORDER BY n DESC LIMIT 10;
    """)

    cur.close()
    conn.close()
    print("\n" + "=" * 78)
    print("done.")
    print("注：avg_residual = total - es_recall - embedding - rerank")
    print("    ≈ literal + doc_match + coarse_evidence + rank + assemble + permission + sensitive")
    print("    coarse_evidence_ms 与 permission_filter_ms 未落库，残差是它们的代理上限。")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"[FATAL] {e}", file=sys.stderr)
        sys.exit(1)
