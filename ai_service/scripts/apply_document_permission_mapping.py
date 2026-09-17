"""
为 kb_document_* 物理索引追加权限投影字段 mapping。

业务功能：
  确保普通文档 chunk 索引具备权限过滤所需的精确匹配字段，避免历史数据回填时依赖 ES 动态映射。

关键流程：
  1. 读取 kb_document_* 匹配到的真实物理索引；
  2. 检查 source_index/index_code/owner_unit_code/visible_unit_codes/permission_version 是否存在；
  3. 缺失字段通过 put_mapping 追加为权限过滤友好的类型；
  4. 已存在但类型不一致时只报告冲突，不覆盖已有 mapping。

执行示例：
  python ai_service/scripts/apply_document_permission_mapping.py
  python ai_service/scripts/apply_document_permission_mapping.py --execute
"""

import argparse
import os
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Tuple

from dotenv import load_dotenv
from elasticsearch import Elasticsearch

load_dotenv()

DEFAULT_ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
DEFAULT_ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
DEFAULT_ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
DEFAULT_INDEX_PATTERN = os.getenv("KB_DOCUMENT_MAPPING_INDEX", "kb_document_*")

PERMISSION_MAPPING = {
    "source_index": {"type": "keyword"},
    "index_code": {"type": "keyword"},
    "owner_unit_code": {"type": "keyword"},
    "visible_unit_codes": {"type": "keyword"},
    "permission_version": {"type": "long"},
}


@dataclass
class IndexMappingPlan:
    """
    业务功能：承载单个索引的 mapping 补齐计划。
    关键流程：missing 表示可以安全追加的字段，conflicts 表示已有字段与预期类型不一致，必须人工处理。
    """

    index: str
    missing: Dict[str, Dict[str, str]] = field(default_factory=dict)
    existing: Dict[str, str] = field(default_factory=dict)
    conflicts: Dict[str, Tuple[str, str]] = field(default_factory=dict)
    applied: bool = False

    @property
    def safe_to_apply(self) -> bool:
        return bool(self.missing) and not self.conflicts


def create_es_client() -> Elasticsearch:
    """
    业务功能：创建 ES 客户端。
    关键流程：沿用项目现有 ES 环境变量，兼容本地和需要 Basic Auth 的部署环境。
    """

    kwargs = {"hosts": [DEFAULT_ES_HOST]}
    if DEFAULT_ES_USER:
        kwargs["basic_auth"] = (DEFAULT_ES_USER, DEFAULT_ES_PASS)
    return Elasticsearch(**kwargs)


def list_physical_indices(es: Elasticsearch, index_pattern: str) -> List[str]:
    """
    业务功能：解析通配表达式对应的真实物理索引。
    关键流程：只返回 kb_document_ 前缀索引，避免误把别名或非普通文档索引纳入权限字段补齐。
    """

    response = es.indices.get(index=index_pattern, expand_wildcards="open")
    indices = sorted(name for name in response.keys() if name.startswith("kb_document_"))
    return indices


def extract_properties(mapping_response: Dict, index: str) -> Dict:
    """
    业务功能：从 ES get_mapping 响应中提取 properties。
    关键流程：不同 ES 客户端版本返回结构一致性较高，但这里仍做空字典兜底，保证 dry-run 能输出可诊断结果。
    """

    return mapping_response.get(index, {}).get("mappings", {}).get("properties", {}) or {}


def build_plan(index: str, properties: Dict) -> IndexMappingPlan:
    """
    业务功能：根据现有 properties 生成单索引 mapping 补齐计划。
    关键流程：缺失字段进入 missing；类型一致字段进入 existing；类型不一致字段进入 conflicts 并阻止自动写入。
    """

    plan = IndexMappingPlan(index=index)
    for field_name, expected_mapping in PERMISSION_MAPPING.items():
        actual_mapping = properties.get(field_name)
        expected_type = expected_mapping["type"]
        if not actual_mapping:
            plan.missing[field_name] = expected_mapping
            continue
        actual_type = actual_mapping.get("type", "")
        if actual_type == expected_type:
            plan.existing[field_name] = actual_type
        else:
            plan.conflicts[field_name] = (actual_type or "<unknown>", expected_type)
    return plan


def build_plans(es: Elasticsearch, index_pattern: str) -> List[IndexMappingPlan]:
    """
    业务功能：为所有匹配索引生成 mapping 补齐计划。
    关键流程：先解析物理索引，再逐个读取 mapping，避免通配 mapping 响应中混入不需要处理的索引。
    """

    plans: List[IndexMappingPlan] = []
    for index in list_physical_indices(es, index_pattern):
        mapping_response = es.indices.get_mapping(index=index)
        plans.append(build_plan(index, extract_properties(mapping_response, index)))
    return plans


def apply_plans(es: Elasticsearch, plans: Iterable[IndexMappingPlan], execute: bool) -> None:
    """
    业务功能：执行或预演 mapping 补齐计划。
    关键流程：只有无冲突且存在缺失字段的索引才允许写入；dry-run 模式只打印计划，不调用 put_mapping。
    """

    for plan in plans:
        if plan.conflicts:
            continue
        if not plan.missing:
            continue
        if execute:
            es.indices.put_mapping(index=plan.index, properties=plan.missing)
            plan.applied = True


def print_plan(plans: Iterable[IndexMappingPlan], execute: bool) -> None:
    """
    业务功能：输出 mapping 计划和执行结果。
    关键流程：将缺失、已存在、冲突分开打印，便于上线前人工确认每个索引的处理状态。
    """

    mode = "execute" if execute else "dry-run"
    print(f"[Document Permission Mapping] mode={mode}")
    for plan in plans:
        print(f"[index] {plan.index}")
        print(f"  missing={','.join(plan.missing.keys()) or '-'}")
        print(f"  existing={','.join(f'{k}:{v}' for k, v in plan.existing.items()) or '-'}")
        print(
            "  conflicts="
            + (
                ",".join(f"{field}:{actual}->{expected}" for field, (actual, expected) in plan.conflicts.items())
                if plan.conflicts
                else "-"
            )
        )
        print(f"  applied={plan.applied}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Apply permission mapping fields to kb_document_* indices.")
    parser.add_argument("--index", default=DEFAULT_INDEX_PATTERN, help="目标索引表达式，默认 kb_document_*")
    parser.add_argument("--execute", action="store_true", help="真实执行 put_mapping；默认只 dry-run")
    return parser.parse_args()


def main() -> None:
    """
    业务功能：命令行入口。
    关键流程：先构建计划，再按 execute 标志决定是否写入，最后输出每个索引的最终状态。
    """

    args = parse_args()
    es = create_es_client()
    plans = build_plans(es, args.index)
    apply_plans(es, plans, args.execute)
    print_plan(plans, args.execute)

    conflict_count = sum(1 for plan in plans if plan.conflicts)
    applied_count = sum(1 for plan in plans if plan.applied)
    missing_count = sum(1 for plan in plans if plan.missing)
    print("[Document Permission Mapping] done")
    print(f"  indices={len(plans)}")
    print(f"  with_missing={missing_count}")
    print(f"  applied={applied_count}")
    print(f"  conflicts={conflict_count}")


if __name__ == "__main__":
    main()
