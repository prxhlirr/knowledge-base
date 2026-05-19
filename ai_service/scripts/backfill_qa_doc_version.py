"""
backfill_qa_doc_version.py — 存量 QA 数据 doc_version 一次性回填脚本（方案B）

业务功能：为 kb_qa_pairs 中所有缺少 doc_version 字段的历史 QA 文档，
          查询其对应的 kb_doc_registry 最新 doc_version 并回填，
          使这些 QA 文档能被 registerDoc 2PC 的版本切换机制正确管理。

关键流程：
  1. 从 kb_qa_pairs 中 scroll 全量 doc_version 为 null 的 QA 文档
  2. 按 source（文件名）分组
  3. 调用 Java 内部接口 /api/v1/internal/doc/registry/latest 查询各文件的最新版本号
  4. 对每组 source 执行 UpdateByQuery，写入 doc_version
  5. 打印回填统计，全量完成后索引进入完整版本管理状态

执行方式：
  python scripts/backfill_qa_doc_version.py

前置条件：
  - ES 服务正常运行
  - JAVA_SERVICE_HOST / KB_INTERNAL_TOKEN 已配置（.env 或环境变量）
  - kb_qa_pairs 索引已通过 ESSetup.init 追加 doc_version 字段（否则先运行 es_init.py）
"""

import os
import sys
import json
import requests
from dotenv import load_dotenv
from elasticsearch import Elasticsearch

load_dotenv()

ES_HOST     = os.getenv("ES_HOST", "http://elasticsearch:9200")
ES_USER     = os.getenv("ES_USER", "")
ES_PASS     = os.getenv("ES_PASS", "")
JAVA_HOST   = os.getenv("JAVA_SERVICE_HOST", "http://127.0.0.1:8080")
INTERNAL_TOKEN = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
QA_INDEX    = "kb_qa_pairs"

# ── 初始化 ES 客户端 ────────────────────────────────────────────────────────────
es_kwargs = {"hosts": [ES_HOST]}
if ES_USER:
    es_kwargs["basic_auth"] = (ES_USER, ES_PASS)
es = Elasticsearch(**es_kwargs)


def get_latest_version_from_java(source_name: str) -> int:
    """
    业务功能：调用 Java 内部接口查询指定文件的最新 doc_version。
    降级：接口异常时返回 1（保守兜底，确保所有历史 QA 被纳入 v1 版本管理）。
    """
    try:
        resp = requests.get(
            f"{JAVA_HOST}/api/v1/internal/doc/registry/by-source",
            params={"sourceName": source_name},
            headers={"X-Internal-Token": INTERNAL_TOKEN},
            timeout=5
        )
        if resp.status_code == 200:
            data = resp.json().get("data", {})
            version = data.get("docVersion", 1)
            return int(version) if version else 1
    except Exception as e:
        print(f"  ⚠️ [Backfill] 查询 Java 版本失败 source={source_name}: {e}")
    return 1  # 保守兜底


def get_distinct_sources_missing_version() -> list:
    """
    业务功能：找出 kb_qa_pairs 中所有 doc_version 字段缺失的文档的 source 去重列表。
    注意：回填脚本执行完后，此方法返回空列表，可用作验证手段。
    """
    agg_query = {
        "size": 0,
        "query": {
            "bool": {
                "must_not": {"exists": {"field": "doc_version"}}
            }
        },
        "aggs": {
            "sources": {
                "terms": {
                    "field": "source",
                    "size": 5000  # 最多 5000 个不同来源文件
                }
            }
        }
    }
    resp = es.search(index=QA_INDEX, body=agg_query)
    buckets = resp["aggregations"]["sources"]["buckets"]
    return [(b["key"], b["doc_count"]) for b in buckets]


def backfill_source(source_name: str, doc_version: int) -> int:
    """
    业务功能：为指定 source 的所有 doc_version=null 的 QA 文档写入 doc_version 值。
    使用 UpdateByQuery + Painless 原子写入，conflicts=proceed。
    返回实际更新的文档数。
    """
    ubq_body = {
        "query": {
            "bool": {
                "must": [
                    {"term": {"source": source_name}},
                    {"bool": {"must_not": {"exists": {"field": "doc_version"}}}}
                ]
            }
        },
        "script": {
            "source": f"ctx._source.doc_version = {doc_version}",
            "lang": "painless"
        }
    }
    resp = es.update_by_query(
        index=QA_INDEX,
        body=ubq_body,
        conflicts="proceed",
        refresh=False  # 批量结束后统一 refresh，减少 IO
    )
    return resp.get("updated", 0)


def main():
    print("=" * 60)
    print("[Backfill] kb_qa_pairs doc_version 存量回填（方案B）")
    print("=" * 60)

    # Step 1: 找出所有缺少 doc_version 的 source 列表
    sources = get_distinct_sources_missing_version()
    if not sources:
        print("[Backfill] kb_qa_pairs 中所有 QA 文档均已有 doc_version，无需回填。")
        return

    print(f"[Backfill] 发现 {len(sources)} 个文件的 QA 数据缺少 doc_version，开始回填...\n")

    total_updated = 0
    failed_sources = []

    for source_name, qa_count in sources:
        # Step 2: 查询 Java 获取最新版本号
        version = get_latest_version_from_java(source_name)
        print(f"  📄 [{source_name}] qa_count={qa_count} → doc_version={version}")

        # Step 3: 回填
        try:
            updated = backfill_source(source_name, version)
            total_updated += updated
            print(f"     ✅ 更新 {updated} 条")
        except Exception as e:
            print(f"     ❌ 回填失败: {e}")
            failed_sources.append(source_name)

    # Step 4: refresh，使回填立即对搜索可见
    es.indices.refresh(index=QA_INDEX)

    # Step 5: 验证
    remaining = get_distinct_sources_missing_version()

    print("\n" + "=" * 60)
    print(f"✅ [Backfill] 完成！总更新: {total_updated} 条 QA 文档")
    if failed_sources:
        print(f"⚠️ [Backfill] 以下 {len(failed_sources)} 个 source 回填失败（需手动处理）:")
        for s in failed_sources:
            print(f"   - {s}")
    if remaining:
        print(f"⚠️ [Backfill] 仍有 {len(remaining)} 个 source 存在缺失（可能是 Java 接口查不到的孤儿文档）:")
        for s, c in remaining[:10]:
            print(f"   - {s} ({c} 条)")
    else:
        print("✅ [Backfill] 所有 QA 文档 doc_version 回填完成，版本管理已全量生效！")
    print("=" * 60)


if __name__ == "__main__":
    main()
