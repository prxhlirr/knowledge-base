#!/usr/bin/env python
"""
业务功能：审计 kb_document_* 索引是否满足主文档索引目标 mapping 与生产检索前置要求。
关键流程：拉取真实 ES mapping/settings/template，逐字段校验类型、analyzer、vector 参数和权限字段。
设计原因：历史索引可能被动态 mapping 污染；进入历史补全或离线迁移前，必须先明确是“只缺数据”还是“索引结构不合格”。
"""

import argparse
import base64
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional


ES_HOST = os.getenv("ES_HOST", "http://127.0.0.1:9200").rstrip("/")
ES_USER = os.getenv("ES_USER", "")
ES_PASS = os.getenv("ES_PASS", "")


def target_field_specs() -> Dict[str, dict]:
    """
    业务功能：定义主文档索引必须满足的目标字段规格。
    关键流程：按当前检索链路实际使用字段定义 required/optional 与类型要求。
    设计原因：审计工具必须以项目真实查询字段为准，不能把辅助索引或 QA 字段机械套到 chunk 索引。
    """
    return {
        "content": {
            "required": True,
            "type": "text",
            "analyzer": "ik_max_word",
            "search_analyzer": "ik_smart",
        },
        "display_content": {
            "required": False,
            "type": "text",
        },
        "vector": {
            "required": True,
            "type": "dense_vector",
            "dims": 1024,
            "similarity": "cosine",
            "index": True,
        },
        "sparse_vector": {
            "required": False,
            "type": "rank_features",
        },
        "acl_tokens": {
            "required": True,
            "type": "keyword",
        },
        "source_index": {
            "required": True,
            "type": "keyword",
        },
        "index_code": {
            "required": True,
            "type": "keyword",
        },
        "owner_unit_code": {
            "required": True,
            "type": "keyword",
        },
        "visible_unit_codes": {
            "required": True,
            "type": "keyword",
        },
        "permission_version": {
            "required": True,
            "type": "long",
        },
        "metadata.is_latest": {
            "required": True,
            "type": "boolean",
        },
        "metadata.doc_version": {
            "required": True,
            "type": "integer",
        },
        "metadata.acl_tokens": {
            "required": False,
            "type": "keyword",
        },
        "metadata.source_index": {
            "required": False,
            "type": "keyword",
        },
        "metadata.visible_unit_codes": {
            "required": False,
            "type": "keyword",
        },
        "metadata.permission_version": {
            "required": False,
            "type": "long",
        },
    }


def es_get(path: str, es_host: str = ES_HOST, es_user: str = ES_USER, es_pass: str = ES_PASS) -> Any:
    """
    业务功能：执行 ES GET 请求并返回 JSON。
    关键流程：使用 urllib，支持 Basic Auth，避免依赖容器中额外 Python 包。
    设计原因：离线生产环境中脚本应尽量少依赖第三方库。
    """
    req = urllib.request.Request(f"{es_host}{path}")
    if es_user:
        token = base64.b64encode(f"{es_user}:{es_pass}".encode("utf-8")).decode("ascii")
        req.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def resolve_indices(pattern: str, es_host: str = ES_HOST) -> List[str]:
    """
    业务功能：解析索引通配符为真实物理索引列表。
    关键流程：调用 _cat/indices，并过滤空索引名。
    设计原因：审计结果必须按物理索引给出，否则无法决定逐索引迁移顺序。
    """
    encoded = urllib.parse.quote(pattern, safe="*,_")
    rows = es_get(f"/_cat/indices/{encoded}?h=index&format=json", es_host=es_host)
    return sorted(row["index"] for row in rows if row.get("index"))


def field_spec(properties: dict, dotted_field: str) -> Optional[dict]:
    """
    业务功能：从 mapping properties 中按点路径取字段定义。
    关键流程：逐级进入 properties，字段不存在返回 None。
    设计原因：主索引 latest/version 字段位于 metadata 下，审计不能只检查根级字段。
    """
    current = properties
    parts = dotted_field.split(".")
    for idx, part in enumerate(parts):
        spec = current.get(part)
        if spec is None:
            return None
        if idx == len(parts) - 1:
            return spec
        current = spec.get("properties", {})
    return None


def check_field(index: str, field: str, actual: Optional[dict], expected: dict) -> List[dict]:
    """
    业务功能：检查单个字段 mapping 是否满足目标规格。
    关键流程：必填字段缺失、类型不匹配、关键参数不匹配均输出 error；可选字段缺失不报错。
    设计原因：迁移判断需要结构化问题列表，而不是只给人工阅读文本。
    """
    issues = []
    if actual is None:
        if expected.get("required"):
            issues.append({
                "severity": "error",
                "index": index,
                "field": field,
                "code": "missing_required_field",
                "message": f"缺少必填字段 {field}",
            })
        return issues

    expected_type = expected.get("type")
    actual_type = actual.get("type")
    if expected_type and actual_type != expected_type:
        issues.append({
            "severity": "error",
            "index": index,
            "field": field,
            "code": "type_mismatch",
            "expected": expected_type,
            "actual": actual_type,
            "message": f"{field} 类型应为 {expected_type}，实际为 {actual_type}",
        })

    for key in ("analyzer", "search_analyzer", "dims", "similarity", "index"):
        if key in expected and actual.get(key) != expected[key]:
            issues.append({
                "severity": "error",
                "index": index,
                "field": field,
                "code": f"{key}_mismatch",
                "expected": expected[key],
                "actual": actual.get(key),
                "message": f"{field}.{key} 应为 {expected[key]}，实际为 {actual.get(key)}",
            })
    return issues


def check_settings(index: str, settings: dict, production: bool) -> List[dict]:
    """
    业务功能：检查索引 settings 是否满足生产容量前置要求。
    关键流程：生产模式下 replicas=0 作为 error；shards=1 作为 warning，提示需要容量评估。
    设计原因：shard 数是否合理取决于数据量，工具应提示风险，但不替代容量规划。
    """
    index_settings = settings.get("settings", {}).get("index", {})
    shards = int(index_settings.get("number_of_shards") or 0)
    replicas = int(index_settings.get("number_of_replicas") or 0)
    issues = []
    if shards <= 1:
        issues.append({
            "severity": "warning",
            "index": index,
            "field": "settings.number_of_shards",
            "code": "single_primary_shard",
            "actual": shards,
            "message": "当前为单主分片，亿级数据前必须做容量评估并按业务索引拆分配置",
        })
    if replicas <= 0:
        issues.append({
            "severity": "error" if production else "warning",
            "index": index,
            "field": "settings.number_of_replicas",
            "code": "zero_replica",
            "actual": replicas,
            "message": "副本数为 0，生产环境无高可用",
        })
    return issues


def check_mapping(index: str, mapping_body: dict, settings_body: dict, production: bool = False) -> dict:
    """
    业务功能：检查单个物理索引 mapping/settings 是否满足目标要求。
    关键流程：字段规格检查 + settings 检查，返回结构化 readiness 结果。
    设计原因：逐索引迁移需要独立门禁，不能只给整体通过/失败。
    """
    mappings = mapping_body.get(index, {}).get("mappings", {})
    properties = mappings.get("properties", {})
    settings = settings_body.get(index, {})
    issues = []
    field_results = {}
    for field, expected in target_field_specs().items():
        actual = field_spec(properties, field)
        field_results[field] = {
            "exists": actual is not None,
            "actual": actual,
            "expected": expected,
        }
        issues.extend(check_field(index, field, actual, expected))
    issues.extend(check_settings(index, settings, production=production))
    errors = [issue for issue in issues if issue["severity"] == "error"]
    warnings = [issue for issue in issues if issue["severity"] == "warning"]
    return {
        "index": index,
        "ready": not errors,
        "errors": errors,
        "warnings": warnings,
        "fields": field_results,
    }


def run_audit(indices_pattern: str, production: bool = False, es_host: str = ES_HOST) -> dict:
    """
    业务功能：执行 kb_document_* mapping readiness 审计。
    关键流程：解析索引列表，逐索引拉取 mapping/settings 并检查。
    设计原因：生产迁移需要按索引拆分风险，尤其是空索引、小索引和大索引的处理策略不同。
    """
    indices = resolve_indices(indices_pattern, es_host=es_host)
    result = {
        "indicesPattern": indices_pattern,
        "productionMode": production,
        "indices": {},
        "summary": {
            "total": len(indices),
            "ready": 0,
            "notReady": 0,
            "errorCount": 0,
            "warningCount": 0,
        },
    }
    for index in indices:
        mapping = es_get(f"/{urllib.parse.quote(index, safe='')}/_mapping", es_host=es_host)
        settings = es_get(f"/{urllib.parse.quote(index, safe='')}/_settings", es_host=es_host)
        item = check_mapping(index, mapping, settings, production=production)
        result["indices"][index] = item
        result["summary"]["ready" if item["ready"] else "notReady"] += 1
        result["summary"]["errorCount"] += len(item["errors"])
        result["summary"]["warningCount"] += len(item["warnings"])
    return result


def run_audit_from_export(export_dir: str, indices_pattern: str = "kb_document_", production: bool = False) -> dict:
    """
    业务功能：基于已导出的 mapping/settings 文件执行离线审计。
    关键流程：读取 raw 目录中的 *.mapping.json 与 *.settings.json，按索引名前缀过滤后复用 check_mapping。
    设计原因：Docker 离线环境或 ES 临时不可达时，仍可基于生产导出包完成 mapping readiness 审批。
    """
    base = Path(export_dir)
    if not base.exists():
        raise FileNotFoundError(f"export_dir not found: {export_dir}")
    prefix = indices_pattern.rstrip("*")
    mapping_files = sorted(base.glob("*.mapping.json"))
    indices = [
        path.name[:-len(".mapping.json")]
        for path in mapping_files
        if path.name.startswith(prefix)
    ]
    result = {
        "indicesPattern": indices_pattern,
        "productionMode": production,
        "source": f"export:{base}",
        "indices": {},
        "summary": {
            "total": len(indices),
            "ready": 0,
            "notReady": 0,
            "errorCount": 0,
            "warningCount": 0,
        },
    }
    for index in indices:
        mapping_path = base / f"{index}.mapping.json"
        settings_path = base / f"{index}.settings.json"
        if not settings_path.exists():
            item = {
                "index": index,
                "ready": False,
                "errors": [{
                    "severity": "error",
                    "index": index,
                    "field": "settings",
                    "code": "missing_settings_export",
                    "message": f"缺少 settings 导出文件: {settings_path}",
                }],
                "warnings": [],
                "fields": {},
            }
        else:
            mapping = json.loads(mapping_path.read_text(encoding="utf-8"))
            settings = json.loads(settings_path.read_text(encoding="utf-8"))
            item = check_mapping(index, mapping, settings, production=production)
        result["indices"][index] = item
        result["summary"]["ready" if item["ready"] else "notReady"] += 1
        result["summary"]["errorCount"] += len(item["errors"])
        result["summary"]["warningCount"] += len(item["warnings"])
    return result


def has_risk(result: dict) -> bool:
    """
    业务功能：判断 mapping readiness 是否存在结构性风险。
    关键流程：任一索引 ready=false 即视为有风险。
    设计原因：字段类型不满足目标 mapping 时，不能进入“只补历史数据”的流程。
    """
    return any(not item.get("ready") for item in result.get("indices", {}).values())


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit kb_document_* mapping readiness.")
    parser.add_argument("--indices", default=os.getenv("SOURCE_INDEX", "kb_document_*"),
                        help="待审计索引或通配符，默认 kb_document_*")
    parser.add_argument("--es-host", default=ES_HOST, help="ES 地址")
    parser.add_argument("--production", action="store_true",
                        help="生产模式：replicas=0 视为 error")
    parser.add_argument("--from-export-dir", default="",
                        help="从已导出的 raw mapping/settings 目录离线审计，不访问 ES")
    parser.add_argument("--output", default="", help="输出 JSON 文件路径")
    parser.add_argument("--fail-on-risk", action="store_true",
                        help="存在 error 时返回非 0 退出码")
    args = parser.parse_args()

    if args.from_export_dir:
        result = run_audit_from_export(args.from_export_dir, args.indices, production=args.production)
    else:
        result = run_audit(args.indices, production=args.production, es_host=args.es_host.rstrip("/"))
    text = json.dumps(result, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
    if args.fail_on_risk and has_risk(result):
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
