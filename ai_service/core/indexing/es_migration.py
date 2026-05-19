#!/usr/bin/env python3
"""
ES 多索引迁移脚本

业务功能：将 10M+ chunks 从单一索引 kb_document_v1 迁移到按文档类型
分区的 5 个专属索引，降低 KNN 检索候选空间，提升召回速度和内存效率。

关键设计：
  1. 零宕机：Reindex 期间旧索引持续对外服务，别名切换在独立脚本中完成
  2. 幂等：目标索引已存在时跳过创建，Reindex 前用 count 检查跳过已完成的分片
  3. slices: auto 自动并行化（利用 ES 主分片数量），最大化迁移吞吐
  4. 分批分组：每个 data_source 组独立执行，失败只影响该组，不影响其他组

使用方式：
  # 第一步：执行迁移（Reindex）
  cd e:/project/AI/knowledge-base
  python ai_service/core/indexing/es_migration.py

  # 第二步：确认结果后切换别名（在 switch_alias.py 中执行）

目标索引布局（5 个物理索引）：
  kb_document_law      → 法律法规 + GA文库 + BM知识
  kb_document_news     → 简报 + 动态 + 各地警务 + 综合要闻 + 时政要闻 + 前沿科技
  kb_document_official → 公文 + 文件 + 批示/督办 + 领导讲话 + 通知 + 传真
  kb_document_public   → 公示 + 公告 + 宣传 + 调查研究
  kb_document_notice   → 重要活动 + 报警内容

  未明确归类（data_source 为空或其他）→ kb_document_official（兜底）
"""

import os
import sys
import time
import argparse
import requests
from datetime import datetime
from elasticsearch import Elasticsearch

# ── 环境变量读取（与 rag_pipeline.py 保持一致）────────────────────────────
ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
ES_USER = os.getenv("ES_USERNAME", "")
ES_PASS = os.getenv("ES_PASSWORD", "")

# ── 源索引（迁移来源）────────────────────────────────────────────────────
SOURCE_INDEX = "kb_document_v1"

# ── Java 跨端通信配置 ────────────────────────────────────────────────────────
JAVA_API_URL = os.getenv("JAVA_SERVICE_HOST", "http://localhost:8080")
INTERNAL_TOKEN = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")

# ── 全局路由配置（从 Java 拉取，不再硬编码）────────────────────────────
INDEX_ROUTING: dict[str, list[str]] = {}
DATA_SOURCE_TO_INDEX: dict[str, str] = {}
FALLBACK_INDEX = "kb_document_official"

def fetch_routing_config() -> None:
    """内部接口调取，同步数据库中最新的路由策略"""
    global INDEX_ROUTING, DATA_SOURCE_TO_INDEX, FALLBACK_INDEX
    url = f"{JAVA_API_URL}/api/v1/internal/routing/active"
    try:
        resp = requests.get(url, headers={"X-Internal-Token": INTERNAL_TOKEN}, timeout=10)
        resp.raise_for_status()
        data = resp.json()
        if data.get("code") == 200 and data.get("data"):
            cache = data["data"]
            # 格式约定: {"法律法规": "kb_document_law", "_FALLBACK_": "kb_document_official"}
            for match_key, target_idx in cache.items():
                if match_key == "_FALLBACK_":
                    FALLBACK_INDEX = target_idx
                    continue
                
                if target_idx not in INDEX_ROUTING:
                    INDEX_ROUTING[target_idx] = []
                INDEX_ROUTING[target_idx].append(match_key)
                DATA_SOURCE_TO_INDEX[match_key] = target_idx
            
            print(f"  ✓ 成功从 Java 服务加载 {len(DATA_SOURCE_TO_INDEX)} 组路由配置 (Fallback: {FALLBACK_INDEX})")
        else:
            raise ValueError(f"响应格式无效或为空: {data}")
    except Exception as e:
        print(f"\n❌ 无法从 Java 内部接口拉取路由配置: {e}")
        print("💡 必须启动 Java 服务且保证 sys_index_routing 存在生效数据")
        sys.exit(1)


def build_es_client() -> Elasticsearch:
    """构建 ES 客户端（支持用户名/密码认证）。"""
    kwargs = {"hosts": [ES_HOST], "request_timeout": 300}
    if ES_USER:
        kwargs["basic_auth"] = (ES_USER, ES_PASS)
    return Elasticsearch(**kwargs)


def build_index_mapping() -> dict:
    """
    构建目标索引的标准 Mapping，与 es_setup.py 中 kb_document_v1 保持完全一致。
    多分片设置（3 主分片）可在千万级 chunk 规模下提升并行查询性能。
    """
    return {
        "settings": {
            "number_of_shards": 3,     # 多分片提升并行 KNN 查询性能
            "number_of_replicas": 0,   # 单节点部署，副本设 0 防 yellow
            "index.max_result_window": 50000,
        },
        "mappings": {
            "properties": {
                "content": {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "search_analyzer": "ik_smart"
                },
                "display_content":   {"type": "text"},
                "chunk_granularity": {"type": "keyword"},
                "parent_chunk_id":   {"type": "keyword"},
                "sparse_vector":     {"type": "rank_features"},
                "vector": {
                    "type": "dense_vector",
                    "dims": 1024,
                    "index": True,
                    "similarity": "cosine"
                },
                "keywords": {"type": "keyword"},
                "metadata": {
                    "properties": {
                        "source":          {"type": "keyword"},
                        "chunk_id":        {"type": "integer"},
                        "is_latest":       {"type": "boolean"},
                        "data_source":     {"type": "keyword"},
                        "owner_dept_id":   {"type": "keyword"},
                        "visible_depts":   {"type": "keyword"},
                        "tags":            {"type": "keyword"},
                        "document_number": {"type": "keyword"},
                        "section_path": {
                            "type": "text",
                            "analyzer": "ik_smart",
                            "fields": {"keyword": {"type": "keyword"}}
                        },
                        "title": {
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart",
                            "fields": {"keyword": {"type": "keyword"}}
                        },
                        "chunk_type":    {"type": "keyword"},
                        "quality_score": {"type": "float"},
                        "owner":         {"type": "keyword"},
                        "search_queries": {"type": "text"},
                        # visibility 权限字段
                        "visibility":    {"type": "keyword"},
                        "dept_code":     {"type": "keyword"},
                        "dept_l2":       {"type": "keyword"},
                        "dept_l4":       {"type": "keyword"},
                        "dept_l6":       {"type": "keyword"},
                        "dept_l9":       {"type": "keyword"},
                        "acl_tokens":    {"type": "keyword"},
                        "dynamic_meta":  {"type": "object", "dynamic": False},
                    }
                }
            }
        }
    }


def create_target_indices(es: Elasticsearch, dry_run: bool = False) -> None:
    """
    幂等创建 5 个目标分区索引，已存在则跳过，不影响生产。

    :param es:      ES 客户端
    :param dry_run: True 时只打印计划，不实际创建
    """
    print("\n" + "═" * 60)
    print("【Step 1】创建目标分区索引")
    print("═" * 60)

    mapping = build_index_mapping()
    for idx_name, data_sources in INDEX_ROUTING.items():
        if es.indices.exists(index=idx_name):
            count = es.count(index=idx_name)["count"]
            print(f"  ✓ {idx_name} 已存在（当前 {count:,} docs），跳过创建")
        else:
            if dry_run:
                print(f"  [DRY-RUN] 将创建: {idx_name}  ← {data_sources}")
            else:
                es.indices.create(index=idx_name, body=mapping)
                print(f"  ✅ 已创建: {idx_name}  ← {data_sources}")


def count_by_source(es: Elasticsearch, source: str) -> int:
    """统计源索引中指定 data_source 的文档数。"""
    res = es.count(
        index=SOURCE_INDEX,
        body={"query": {"term": {"metadata.data_source": source}}}
    )
    return res["count"]


def run_reindex_group(
    es: Elasticsearch,
    target_index: str,
    data_sources: list[str],
    dry_run: bool = False
) -> dict:
    """
    将一组 data_source 的文档从 SOURCE_INDEX Reindex 到 target_index。

    关键参数：
      - slices: "auto"   并行 Reindex（ES 自动按主分片数切分）
      - wait_for_completion: True  等待完成再返回（迁移窗口执行，可接受阻塞）
      - conflicts: "proceed"       遇文档 version conflict 时跳过，不报错

    :return: {target_index, total, failures, elapsed_s}
    """
    t0 = time.time()
    total_src = sum(count_by_source(es, ds) for ds in data_sources)
    target_cur = es.count(index=target_index)["count"]

    print(f"\n  → {target_index}")
    print(f"     源文档数: {total_src:,}  |  目标当前: {target_cur:,}")

    # 跳过条件：目标已有数据且与源相差 < 1%（避免重复迁移）
    if target_cur > 0 and abs(target_cur - total_src) / max(total_src, 1) < 0.01:
        print(f"     ⏭️  目标已接近完整，跳过 Reindex（差异 < 1%）")
        return {"target": target_index, "total": target_cur, "failures": 0, "elapsed_s": 0}

    if dry_run:
        print(f"     [DRY-RUN] 将 Reindex {total_src:,} 文档 → {target_index}")
        return {"target": target_index, "total": total_src, "failures": 0, "elapsed_s": 0}

    # 过滤条件：只迁移指定 data_source 的文档
    query = {
        "bool": {
            "should": [
                {"term": {"metadata.data_source": ds}} for ds in data_sources
            ],
            "minimum_should_match": 1
        }
    }

    body = {
        "source": {
            "index": SOURCE_INDEX,
            "query": query,
            "size": 500,          # 每批 500 文档（含 1024 维向量，防 bulk 内存过大）
        },
        "dest": {
            "index": target_index,
            "version_type": "external_gte",   # 保留原始版本号，天然幂等
        },
        "conflicts": "proceed",
    }

    # 解决 DeprecationWarning，新版 es sdk 推荐用 options(...)传递 timeout
    resp = es.options(request_timeout=7200).reindex(
        body=body,
        slices="auto",                        # 并行 Reindex
        wait_for_completion=True,
    )

    # 强制刷新该分片索引以确保数据被后续统计实时可见
    es.indices.refresh(index=target_index)

    elapsed = time.time() - t0
    failures = len(resp.get("failures", []))
    total    = resp.get("total", 0)
    created  = resp.get("created", 0)
    updated  = resp.get("updated", 0)

    status = "✅" if failures == 0 else "⚠️"
    print(f"     {status} 完成 | total={total:,} created={created:,} "
          f"updated={updated:,} failures={failures} | 耗时 {elapsed:.1f}s")

    if failures > 0:
        print(f"     失败详情（前 5 条）: {resp['failures'][:5]}")

    return {"target": target_index, "total": total, "failures": failures, "elapsed_s": elapsed}


def run_fallback_reindex(
    es: Elasticsearch,
    all_known_sources: list[str],
    dry_run: bool = False
) -> dict:
    """
    兜底 Reindex：将未归类的文档（data_source 不在已知列表中，或为空）
    全部迁移到 kb_document_official。

    关键设计：must_not + terms 排除已路由的文档，捞剩余全部。
    """
    print(f"\n  → {FALLBACK_INDEX} (兜底：未命中文档类型)")

    # 排除已知 data_source，剩余落到 official 兜底索引
    query = {
        "bool": {
            "must_not": [
                {"terms": {"metadata.data_source": all_known_sources}}
            ]
        }
    }

    src_count = es.count(index=SOURCE_INDEX, body={"query": query})["count"]
    print(f"     未归类文档数: {src_count:,}")

    if src_count == 0:
        print("     ✓ 无未归类文档，跳过")
        return {"target": FALLBACK_INDEX, "total": 0, "failures": 0, "elapsed_s": 0}

    if dry_run:
        print(f"     [DRY-RUN] 将迁移 {src_count:,} 条未归类文档 → {FALLBACK_INDEX}")
        return {"target": FALLBACK_INDEX, "total": src_count, "failures": 0, "elapsed_s": 0}

    t0 = time.time()
    resp = es.options(request_timeout=7200).reindex(
        body={
            "source": {"index": SOURCE_INDEX, "query": query, "size": 500},
            "dest":   {"index": FALLBACK_INDEX, "version_type": "external_gte"},
            "conflicts": "proceed",
        },
        slices="auto",
        wait_for_completion=True,
    )

    # 强制刷新该分片索引以确保数据被后续统计实时可见
    es.indices.refresh(index=FALLBACK_INDEX)

    elapsed  = time.time() - t0
    failures = len(resp.get("failures", []))
    total    = resp.get("total", 0)
    print(f"     {'✅' if not failures else '⚠️'} 兜底完成 | total={total:,} failures={failures} | 耗时 {elapsed:.1f}s")

    return {"target": FALLBACK_INDEX, "total": total, "failures": failures, "elapsed_s": elapsed}


def print_summary(es: Elasticsearch, results: list[dict], total_elapsed: float) -> None:
    """打印迁移结果汇总，并对比源索引总量做完整性验证。"""
    print("\n" + "═" * 60)
    print("【迁移结果汇总】")
    print("═" * 60)

    src_total = es.count(index=SOURCE_INDEX)["count"]
    migrated  = 0
    all_ok    = True

    print(f"\n  源索引 {SOURCE_INDEX}: {src_total:,} docs\n")
    for r in results:
        target_count = es.count(index=r["target"])["count"]
        status = "✅" if r["failures"] == 0 else "⚠️"
        print(f"  {status} {r['target']:<30} {target_count:>10,} docs")
        migrated += target_count
        if r["failures"] > 0:
            all_ok = False

    coverage = migrated / max(src_total, 1) * 100
    print(f"\n  合计迁移: {migrated:,} / {src_total:,}  ({coverage:.1f}% 覆盖率)")
    print(f"  总耗时:   {total_elapsed:.1f}s ({total_elapsed/60:.1f} 分钟)")

    if coverage >= 99.9:
        print("\n  🎉 迁移完整性验证通过（覆盖率 ≥ 99.9%）")
        print("  下一步：运行 scripts/switch_alias.py 完成别名切换")
    else:
        print(f"\n  ⚠️  覆盖率低于 99.9%，差异 {src_total - migrated:,} 条，请排查后再执行别名切换")


def main():
    parser = argparse.ArgumentParser(
        description="知识库 ES 多索引迁移工具（单索引 → 5 分区索引）"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="预演模式：只打印计划，不实际操作"
    )
    parser.add_argument(
        "--only", type=str, default=None,
        help="只迁移指定目标索引，如 --only kb_document_law"
    )
    args = parser.parse_args()

    print(f"\n{'═'*60}")
    print(f"  知识库 ES 多索引迁移")
    print(f"  启动时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  源索引:   {SOURCE_INDEX}")
    print(f"  ES地址:   {ES_HOST}")
    if args.dry_run:
        print("  ⚠️  [DRY-RUN 模式] 不会执行任何写操作")
    print(f"{'═'*60}")
    
    # 获取服务端路由配置
    fetch_routing_config()

    es = build_es_client()

    # 连通性检查
    try:
        info = es.info()
        print(f"\n✅ ES 连接成功: {info['version']['number']}")
    except Exception as e:
        print(f"\n❌ ES 连接失败: {e}")
        sys.exit(1)

    # 源索引检查
    if not es.indices.exists(index=SOURCE_INDEX):
        print(f"\n❌ 源索引 {SOURCE_INDEX} 不存在，迁移中止")
        sys.exit(1)

    src_count = es.count(index=SOURCE_INDEX)["count"]
    print(f"📊 源索引文档总数: {src_count:,}")

    # Step1: 创建目标索引
    create_target_indices(es, dry_run=args.dry_run)

    # Step2: 按组 Reindex
    print("\n" + "═" * 60)
    print("【Step 2】Reindex（按 data_source 分组）")
    print("═" * 60)
    print("  ⚠️  含 1024 维向量，单次 Reindex 耗时可能较长，请保持脚本运行\n")

    t_total = time.time()
    results: list[dict] = []
    all_known_sources: list[str] = []

    for target_idx, data_sources in INDEX_ROUTING.items():
        # --only 过滤
        if args.only and args.only != target_idx:
            continue

        all_known_sources.extend(data_sources)
        result = run_reindex_group(
            es, target_idx, data_sources, dry_run=args.dry_run
        )
        results.append(result)

    # 兜底：未归类文档
    if not args.only:
        result = run_fallback_reindex(
            es, all_known_sources, dry_run=args.dry_run
        )
        # 兜底结果合并到 official（避免重复计数）
        # 只附加用于展示，actual 已计入 official
        results.append({**result, "target": f"{FALLBACK_INDEX}（兜底）"})

    total_elapsed = time.time() - t_total

    # Step3: 汇总验证
    if not args.dry_run:
        print_summary(es, [r for r in results if "兜底" not in r.get("target", "")], total_elapsed)
    else:
        print(f"\n[DRY-RUN 完成] 实际运行无 --dry-run 参数即可执行")


if __name__ == "__main__":
    main()
