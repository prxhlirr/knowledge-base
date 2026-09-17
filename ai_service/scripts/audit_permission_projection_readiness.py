#!/usr/bin/env python
"""
业务功能：审计 ES 中权限投影字段是否已具备关闭历史兼容开关的条件。
关键流程：对 chunk/doc_search/doc_meta/QA 索引分别统计最新文档中缺失 acl_tokens/source_index/visible_unit_codes 的数量。
设计原因：关闭 legacy-missing-permission/source_index 兼容前必须先量化历史缺字段风险，避免直接切严格模式造成漏召回。
"""

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from typing import Dict, List


ES_HOST = os.getenv("ES_HOST", "http://127.0.0.1:9200").rstrip("/")
ES_USER = os.getenv("ES_USER", "")
ES_PASS = os.getenv("ES_PASS", "")


def latest_filter(field: str) -> dict:
    """
    业务功能：构造兼容历史数据的 latest 过滤。
    关键流程：字段为 true 或字段不存在均纳入统计，避免老数据因没有 is_latest/doc_version 被漏审。
    设计原因：审计目标是发现历史风险，不能只检查新结构数据。
    """
    return {
        "bool": {
            "should": [
                {"term": {field: True}},
                {"bool": {"must_not": [{"exists": {"field": field}}]}},
            ],
            "minimum_should_match": 1,
        }
    }


def missing_field_query(field: str, latest_field: str) -> dict:
    """
    业务功能：构造某个权限投影字段缺失的 count 查询。
    关键流程：限定最新文档范围后，统计字段不存在的记录。
    设计原因：只有 latest 数据会参与在线检索，审计应聚焦真实风险面。
    """
    return {
        "query": {
            "bool": {
                "filter": [latest_filter(latest_field)],
                "must_not": [{"exists": {"field": field}}],
            }
        }
    }


def audit_targets() -> List[dict]:
    """
    业务功能：定义各类检索索引的权限投影审计目标。
    关键流程：按索引结构区分 latest 字段路径和权限字段路径。
    设计原因：chunk 使用 metadata.is_latest，辅助索引使用顶层 is_latest，不能混用查询字段。
    """
    return [
        {
            "name": "chunk",
            "index": os.getenv("SOURCE_INDEX", "kb_document_*"),
            "latest_field": "metadata.is_latest",
            "fields": ["acl_tokens", "source_index", "visible_unit_codes"],
        },
        {
            "name": "doc_search",
            "index": os.getenv("KB_DOC_SEARCH_READ_ALIAS", "kb_doc_search"),
            "latest_field": "is_latest",
            "fields": ["acl_tokens", "source_index", "visible_unit_codes"],
        },
        {
            "name": "doc_meta",
            "index": os.getenv("KB_DOC_META_READ_ALIAS", "kb_doc_meta"),
            "latest_field": "is_latest",
            "fields": ["acl_tokens", "source_index", "visible_unit_codes"],
        },
        {
            "name": "qa",
            "index": os.getenv("QA_INDEX_PATTERN", "kb_qa_*"),
            "latest_field": "is_latest",
            "fields": ["acl_tokens", "source_index", "visible_unit_codes"],
        },
    ]


def es_count(index: str, body: dict) -> int:
    """
    业务功能：执行 ES _count 请求并返回数量。
    关键流程：使用标准库 urllib 发送 POST，支持 ES_USER/ES_PASS Basic Auth。
    设计原因：审计脚本应尽量少依赖项目运行时，便于在生产运维环境直接执行。
    """
    url = f"{ES_HOST}/{urllib.parse.quote(index, safe='*,_')}/_count"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    if ES_USER:
        import base64
        token = base64.b64encode(f"{ES_USER}:{ES_PASS}".encode("utf-8")).decode("ascii")
        req.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    return int(payload.get("count") or 0)


def run_audit() -> Dict[str, dict]:
    """
    业务功能：执行所有索引的权限投影缺字段统计。
    关键流程：遍历审计目标和字段，返回结构化 JSON 结果。
    设计原因：上线前需要可机器读取的结果，用于 CI、巡检或人工审批。
    """
    result = {}
    for target in audit_targets():
        item = {
            "index": target["index"],
            "latestField": target["latest_field"],
            "missing": {},
        }
        for field in target["fields"]:
            try:
                item["missing"][field] = es_count(
                    target["index"],
                    missing_field_query(field, target["latest_field"]),
                )
            except Exception as exc:
                item["missing"][field] = None
                item["error"] = str(exc)
        result[target["name"]] = item
    return result


def has_risk(result: Dict[str, dict]) -> bool:
    """
    业务功能：判断审计结果是否仍存在关闭兼容开关风险。
    关键流程：任一字段缺失数量为 None 或大于 0，即视为仍有风险。
    设计原因：严格模式必须 fail-closed，不能在审计异常时误判为安全。
    """
    for item in result.values():
        for count in item.get("missing", {}).values():
            if count is None or int(count) > 0:
                return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit ES permission projection readiness.")
    parser.add_argument("--fail-on-risk", action="store_true",
                        help="存在缺字段或审计异常时返回非 0 退出码")
    args = parser.parse_args()

    result = run_audit()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.fail_on_risk and has_risk(result):
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
