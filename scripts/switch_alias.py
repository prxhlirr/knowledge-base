#!/usr/bin/env python3
"""
ES 别名原子切换脚本

业务功能：在 Reindex 完成并验证无误后，将搜索流量从旧单索引
kb_document_v1 无缝切换到新的 5 个分区索引，全程零宕机。

关键设计：
  1. 原子性：ES /_aliases 接口保证 add/remove 在单个 CPU tick 内完成，
     切换瞬间无请求落入既无新索引也无旧索引的"真空期"
  2. 可回滚：--rollback 参数可一键将别名切回 kb_document_v1，
     回滚前保留旧索引（不删除），确保随时可用
  3. 安全门控：切换前自动检查各分区索引的文档数，覆盖率 < 99% 时
     拒绝切换并给出操作建议，防止因迁移不完整导致数据丢失
  4. 预演模式：--dry-run 只打印操作计划，不修改任何 ES 状态

使用流程：
  # 1. 执行 Reindex（如未执行）
  python ai_service/core/indexing/es_migration.py

  # 2. 预演别名切换（确认无误再执行）
  python scripts/switch_alias.py --dry-run

  # 3. 正式切换
  python scripts/switch_alias.py

  # 4. 验证搜索走新索引
  curl http://localhost:9200/kb_document/_count

  # 5. 如需回滚
  python scripts/switch_alias.py --rollback

注意事项：
  - 别名切换后旧索引 kb_document_v1 仍保留（不自动删除），
    确认业务稳定后可手动删除以释放磁盘空间
  - 生产环境执行前确保 ES_HOST / ES_USERNAME / ES_PASSWORD 环境变量已设置
"""

import os
import sys
import argparse
from datetime import datetime
from elasticsearch import Elasticsearch

# ── 环境变量（与 es_migration.py 和 rag_pipeline.py 保持一致）─────────────
ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
ES_USER = os.getenv("ES_USERNAME", "")
ES_PASS = os.getenv("ES_PASSWORD", "")

# ── 别名名称（Java 和 AI 服务的统一搜索入口）────────────────────────────────
ALIAS_NAME   = "kb_document"

# ── 旧索引（迁移来源）────────────────────────────────────────────────────
OLD_INDEX    = "kb_document_v1"

# ── 新分区索引（迁移目标）────────────────────────────────────────────────
NEW_INDICES  = [
    "kb_document_law",
    "kb_document_news",
    "kb_document_official",
    "kb_document_public",
    "kb_document_notice",
]

# ── 安全门：各分区覆盖率须达到此阈值才允许切换 ────────────────────────────
MIN_COVERAGE = 0.99     # 99%


def build_es_client() -> Elasticsearch:
    kwargs = {"hosts": [ES_HOST], "request_timeout": 60}
    if ES_USER:
        kwargs["basic_auth"] = (ES_USER, ES_PASS)
    return Elasticsearch(**kwargs)


def check_and_print_status(es: Elasticsearch) -> tuple[bool, int, int]:
    """
    检查当前别名状态和各分区索引文档数。

    关键流程：
      1. 查询旧索引文档总数（作为基准）
      2. 统计各分区索引文档数之和
      3. 计算覆盖率，返回是否满足安全门控

    :return: (is_safe, old_count, new_total)
    """
    print("\n" + "═" * 65)
    print("  索引状态检查")
    print("═" * 65)

    # 当前别名指向哪些索引
    try:
        alias_info = es.indices.get_alias(name=ALIAS_NAME)
        current_alias_indices = list(alias_info.keys())
        print(f"\n  当前别名 [{ALIAS_NAME}] → {current_alias_indices}")
    except Exception:
        current_alias_indices = []
        print(f"\n  当前别名 [{ALIAS_NAME}] 不存在（首次切换）")

    # 旧索引文档数
    if es.indices.exists(index=OLD_INDEX):
        old_count = es.count(index=OLD_INDEX)["count"]
        print(f"\n  旧索引 {OLD_INDEX}: {old_count:,} docs")
    else:
        old_count = 0
        print(f"\n  旧索引 {OLD_INDEX}: 不存在")

    # 各分区索引文档数
    new_total = 0
    print(f"\n  新分区索引:")
    all_exist = True
    for idx in NEW_INDICES:
        if es.indices.exists(index=idx):
            cnt = es.count(index=idx)["count"]
            new_total += cnt
            print(f"    {'✅' if cnt > 0 else '⚠️'} {idx:<30} {cnt:>10,} docs")
        else:
            all_exist = False
            print(f"    ❌ {idx:<30} 不存在（请先执行 es_migration.py）")

    if not all_exist:
        return False, old_count, new_total

    coverage = new_total / max(old_count, 1) * 100
    print(f"\n  合计新索引: {new_total:,} docs")
    print(f"  覆盖率:     {coverage:.2f}%  （安全门: ≥ {MIN_COVERAGE*100:.0f}%）")

    is_safe = (old_count == 0 or new_total / old_count >= MIN_COVERAGE)
    return is_safe, old_count, new_total


def do_switch(es: Elasticsearch, dry_run: bool = False) -> None:
    """
    执行别名原子切换：
      1. Remove kb_document → kb_document_v1
      2. Add    kb_document → 5 个分区索引
    两步在一个 /_aliases 请求中原子执行。

    :param dry_run: True 时只输出操作计划，不实际修改
    """
    print("\n" + "═" * 65)
    print("  执行别名切换")
    print("═" * 65)

    # 构建 actions（原子操作集）
    actions = []

    # Step A：移除旧别名（如存在）
    if es.indices.exists(index=OLD_INDEX):
        try:
            old_aliases = es.indices.get_alias(index=OLD_INDEX)
            if ALIAS_NAME in old_aliases.get(OLD_INDEX, {}).get("aliases", {}):
                actions.append({
                    "remove": {"index": OLD_INDEX, "alias": ALIAS_NAME}
                })
                print(f"\n  REMOVE: {ALIAS_NAME} → {OLD_INDEX}")
        except Exception:
            pass  # 旧别名不存在，跳过 remove

    # Step B：添加新别名
    for idx in NEW_INDICES:
        actions.append({"add": {"index": idx, "alias": ALIAS_NAME}})
        print(f"  ADD:    {ALIAS_NAME} → {idx}")

    if not actions:
        print("\n  ⚠️  无需操作（所有 add/remove 均已是目标状态）")
        return

    if dry_run:
        print(f"\n  [DRY-RUN] 以上 {len(actions)} 个操作将在一个原子请求中执行")
        return

    # 原子执行
    es.indices.update_aliases(body={"actions": actions})
    print(f"\n  ✅ 别名切换成功（{len(actions)} 个操作，原子完成）")


def do_rollback(es: Elasticsearch, dry_run: bool = False) -> None:
    """
    回滚别名：将 kb_document 切回 kb_document_v1。
    仅在旧索引仍存在时可操作。

    :param dry_run: True 时只输出回滚计划
    """
    print("\n" + "═" * 65)
    print("  执行别名回滚")
    print("═" * 65)

    if not es.indices.exists(index=OLD_INDEX):
        print(f"\n  ❌ 旧索引 {OLD_INDEX} 不存在，已无法回滚")
        print("  建议：如旧索引已删除，需重新 Reindex 才能回滚")
        return

    actions = []

    # 移除新索引的别名
    for idx in NEW_INDICES:
        if es.indices.exists(index=idx):
            try:
                idx_aliases = es.indices.get_alias(index=idx)
                if ALIAS_NAME in idx_aliases.get(idx, {}).get("aliases", {}):
                    actions.append({"remove": {"index": idx, "alias": ALIAS_NAME}})
                    print(f"\n  REMOVE: {ALIAS_NAME} → {idx}")
            except Exception:
                pass

    # 添加旧索引别名
    actions.append({"add": {"index": OLD_INDEX, "alias": ALIAS_NAME}})
    print(f"  ADD:    {ALIAS_NAME} → {OLD_INDEX}")

    if dry_run:
        print(f"\n  [DRY-RUN] 以上 {len(actions)} 个操作将在一个原子请求中执行（回滚模式）")
        return

    es.indices.update_aliases(body={"actions": actions})
    old_count = es.count(index=OLD_INDEX)["count"]
    print(f"\n  ✅ 回滚成功：{ALIAS_NAME} → {OLD_INDEX} ({old_count:,} docs)")


def verify_alias(es: Elasticsearch) -> None:
    """切换后验证：打印别名最终状态和文档总数。"""
    print("\n" + "═" * 65)
    print("  验证结果")
    print("═" * 65)

    try:
        alias_info = es.indices.get_alias(name=ALIAS_NAME)
        indices_in_alias = list(alias_info.keys())
        print(f"\n  别名 [{ALIAS_NAME}] 当前指向:")
        for idx in indices_in_alias:
            cnt = es.count(index=idx)["count"]
            print(f"    - {idx}: {cnt:,} docs")

        total = es.count(index=ALIAS_NAME)["count"]
        print(f"\n  通过别名查询总数: {total:,} docs")
        print(f"\n  ✅ 验证完成。")
        if indices_in_alias == [OLD_INDEX]:
            print("  ℹ️  提示：当前别名仍指向旧索引，如需切换请去掉 --rollback 参数重新执行")
        else:
            print(f"  ℹ️  旧索引 {OLD_INDEX} 已不在别名中，确认业务稳定后可手动删除以释放磁盘")
    except Exception as e:
        print(f"\n  ⚠️  无法获取别名信息: {e}")


def main():
    parser = argparse.ArgumentParser(
        description="知识库 ES 别名原子切换工具（单索引 → 5 分区索引）"
    )
    parser.add_argument("--dry-run",  action="store_true", help="预演模式，不修改 ES 状态")
    parser.add_argument("--rollback", action="store_true", help="回滚别名到旧索引 kb_document_v1")
    parser.add_argument("--force",    action="store_true", help="忽略覆盖率告警强制切换（不推荐）")
    args = parser.parse_args()

    print(f"\n{'═'*65}")
    print(f"  ES 别名切换工具")
    print(f"  时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  ES:   {ES_HOST}")
    if args.rollback: print("  模式: ⏪ 回滚")
    elif args.dry_run: print("  模式: 🔍 预演（不修改）")
    else:              print("  模式: 🚀 正式切换")
    print(f"{'═'*65}")

    es = build_es_client()

    # 连通性检查
    try:
        es.info()
        print("\n✅ ES 连接成功")
    except Exception as e:
        print(f"\n❌ ES 连接失败: {e}")
        sys.exit(1)

    if args.rollback:
        do_rollback(es, dry_run=args.dry_run)
        if not args.dry_run:
            verify_alias(es)
        return

    # 正式切换前：安全门控检查
    is_safe, old_count, new_total = check_and_print_status(es)

    if not is_safe and not args.force:
        print("\n❌ 安全门控未通过，切换已取消")
        print("   建议：")
        print("   1. 运行 es_migration.py 完成 Reindex")
        print("   2. 确认覆盖率 ≥ 99% 后再执行切换")
        print("   3. 如需强制切换（不推荐），添加 --force 参数")
        sys.exit(1)

    if not is_safe and args.force:
        print("\n⚠️  覆盖率未达标，但 --force 已指定，强制切换中...")

    do_switch(es, dry_run=args.dry_run)

    if not args.dry_run:
        verify_alias(es)


if __name__ == "__main__":
    main()
