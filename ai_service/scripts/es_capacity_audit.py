#!/usr/bin/env python3
"""
业务功能：离线审计 Elasticsearch 索引容量、分片、mapping 和 chunk 字段大小。
关键流程：只通过 GET/POST 查询 ES 元数据和少量样本，不执行任何创建、删除、更新或 alias 切换操作；
          输出 JSON/Markdown/CSV 报告，辅助亿级 chunks 下的 shard 和检索架构规划。
"""

import base64
import csv
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


DEFAULT_INDEX_PATTERN = "kb_document*"
DEFAULT_OUTPUT_DIR = "reports/es_capacity"
DEFAULT_SAMPLE_SIZE = 1000
DEFAULT_TIMEOUT_SECONDS = 30
VECTOR_FIELD_CANDIDATES = ("vector", "question_vector", "doc_vector", "colloquial_vector")
TEXT_FIELD_CANDIDATES = ("content", "question", "answer_content", "summary", "title")
SIZE_TARGETS = (50_000_000, 100_000_000, 200_000_000)


def env_int(name: str, default: int, min_value: int = 0) -> int:
    """
    业务功能：读取整数型环境变量。
    关键流程：离线脚本经常由不同机器手工执行，非法输入回退默认值能保证审计不中断。
    """
    raw = os.getenv(name)
    if raw is None or str(raw).strip() == "":
        return default
    try:
        value = int(str(raw).strip())
    except ValueError:
        return default
    return value if value >= min_value else default


def bytes_to_human(value: Optional[float]) -> str:
    """
    业务功能：把字节数格式化成人类可读的容量。
    关键流程：报告同时保留原始 byte 和可读格式，避免后续做容量模型时丢精度。
    """
    if value is None:
        return "-"
    units = ["B", "KB", "MB", "GB", "TB", "PB"]
    size = float(value)
    for unit in units:
        if abs(size) < 1024 or unit == units[-1]:
            return f"{size:.2f}{unit}"
        size /= 1024
    return f"{size:.2f}PB"


def percentile(values: List[int], pct: float) -> Optional[float]:
    """
    业务功能：计算样本分位数。
    关键流程：chunk 大小通常长尾明显，p95/p99 比平均值更能暴露超大 source 对查询和网络的影响。
    """
    if not values:
        return None
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct))
    return float(ordered[index])


class EsClient:
    """
    业务功能：提供最小 ES HTTP 客户端。
    关键流程：仅依赖 Python 标准库，保证离线环境无需 pip install 也可以运行。
    """

    def __init__(self, base_url: str, username: str = "", password: str = "", timeout: int = DEFAULT_TIMEOUT_SECONDS):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.headers = {"Content-Type": "application/json"}
        if username or password:
            token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
            self.headers["Authorization"] = f"Basic {token}"

    def request(self, method: str, path: str, body: Optional[Dict[str, Any]] = None) -> Any:
        """
        业务功能：执行 ES HTTP 请求并解析 JSON。
        关键流程：统一拼接 URL、认证头和错误信息，让审计主体逻辑只关注 ES 数据结构。
        """
        url = self.base_url + path
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=self.headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                payload = resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"ES HTTP {exc.code} {method} {path}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"ES connection failed {method} {path}: {exc}") from exc
        if not payload.strip():
            return {}
        return json.loads(payload)


def parse_int(value: Any, default: int = 0) -> int:
    """
    业务功能：宽松解析 ES cat API 返回的数字。
    关键流程：cat API 全部是字符串，集中处理能避免空值或非法值影响报告生成。
    """
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def flatten_properties(properties: Dict[str, Any], prefix: str = "") -> Dict[str, Dict[str, Any]]:
    """
    业务功能：展开 mapping properties。
    关键流程：metadata 下的权限字段和正文扩展字段也要纳入审计，递归展开可以统一发现字段类型。
    """
    result: Dict[str, Dict[str, Any]] = {}
    for name, spec in properties.items():
        path = f"{prefix}.{name}" if prefix else name
        result[path] = spec
        nested = spec.get("properties")
        if isinstance(nested, dict):
            result.update(flatten_properties(nested, path))
    return result


def extract_mapping_summary(mapping_body: Dict[str, Any], index: str) -> Dict[str, Any]:
    """
    业务功能：提取索引 mapping 中对容量和检索最关键的字段信息。
    关键流程：重点关注 dense_vector、权限字段和候选文本字段，避免报告被完整 mapping 淹没。
    """
    mappings = mapping_body.get(index, {}).get("mappings", {})
    props = flatten_properties(mappings.get("properties", {}))
    vector_fields = []
    permission_fields = {}
    text_fields = []
    for field, spec in props.items():
        field_type = spec.get("type")
        if field_type == "dense_vector":
            vector_fields.append(
                {
                    "field": field,
                    "dims": spec.get("dims"),
                    "index": spec.get("index"),
                    "similarity": spec.get("similarity"),
                    "index_options": spec.get("index_options", {}),
                }
            )
        if field in {"acl_tokens", "source_index", "index_code", "owner_unit_code", "visible_unit_codes", "permission_version"}:
            permission_fields[field] = field_type
        if field_type == "text" and any(field.endswith(candidate) for candidate in TEXT_FIELD_CANDIDATES):
            text_fields.append(field)
    return {
        "vector_fields": vector_fields,
        "permission_fields": permission_fields,
        "text_fields": sorted(text_fields),
        "field_count": len(props),
    }


def sample_index(es: EsClient, index: str, sample_size: int) -> Dict[str, Any]:
    """
    业务功能：抽样估算 chunk 文本大小和 _source 大小。
    关键流程：使用 size 查询前 N 条样本，优先统计常见正文/向量字段；样本只用于容量估算，不改变 ES 数据。
    """
    if sample_size <= 0:
        return {"sampled": 0, "source_bytes": {}, "text_bytes": {}, "vector_dims_seen": {}}

    body = {
        "size": sample_size,
        "track_total_hits": False,
        "query": {"match_all": {}},
    }
    result = es.request("POST", f"/{urllib.parse.quote(index)}/_search", body)
    hits = result.get("hits", {}).get("hits", [])
    source_sizes: List[int] = []
    text_sizes: Dict[str, List[int]] = {field: [] for field in TEXT_FIELD_CANDIDATES}
    vector_dims_seen: Dict[str, List[int]] = {field: [] for field in VECTOR_FIELD_CANDIDATES}

    for hit in hits:
        source = hit.get("_source", {})
        raw = json.dumps(source, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        source_sizes.append(len(raw))
        for field in TEXT_FIELD_CANDIDATES:
            value = source.get(field)
            if isinstance(value, str):
                text_sizes[field].append(len(value.encode("utf-8")))
        for field in VECTOR_FIELD_CANDIDATES:
            value = source.get(field)
            if isinstance(value, list):
                vector_dims_seen[field].append(len(value))

    return {
        "sampled": len(hits),
        "source_bytes": summarize_numbers(source_sizes),
        "text_bytes": {field: summarize_numbers(values) for field, values in text_sizes.items() if values},
        "vector_dims_seen": {
            field: sorted(set(values))[:10] for field, values in vector_dims_seen.items() if values
        },
    }


def summarize_numbers(values: List[int]) -> Dict[str, Any]:
    """
    业务功能：汇总数值样本。
    关键流程：同时输出 average/p50/p95/p99/max，辅助识别长尾 chunk 和异常 metadata 膨胀。
    """
    if not values:
        return {"count": 0}
    return {
        "count": len(values),
        "avg": statistics.mean(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values),
    }


def collect_indices(es: EsClient, pattern: str) -> List[Dict[str, Any]]:
    """
    业务功能：收集索引级容量与文档数。
    关键流程：使用 bytes=b 保留字节精度，后续 markdown 再转成人类可读容量。
    """
    encoded = urllib.parse.quote(pattern, safe="*,")
    rows = es.request("GET", f"/_cat/indices/{encoded}?format=json&bytes=b")
    for row in rows:
        row["docs.count"] = parse_int(row.get("docs.count"))
        row["pri.store.size"] = parse_int(row.get("pri.store.size"))
        row["store.size"] = parse_int(row.get("store.size"))
        row["pri"] = parse_int(row.get("pri"))
        row["rep"] = parse_int(row.get("rep"))
    return rows


def collect_shards(es: EsClient, pattern: str) -> List[Dict[str, Any]]:
    """
    业务功能：收集 shard 级容量分布。
    关键流程：最大 shard 和平均 shard 是判断分片是否合理的核心指标，必须脱离索引总量单独计算。
    """
    encoded = urllib.parse.quote(pattern, safe="*,")
    rows = es.request("GET", f"/_cat/shards/{encoded}?format=json&bytes=b")
    for row in rows:
        row["docs"] = parse_int(row.get("docs"))
        row["store"] = parse_int(row.get("store"))
    return rows


def shard_summary(shards: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    """
    业务功能：按索引汇总 primary shard 分布。
    关键流程：只统计 primary shard，因为 primary 数决定写入和 reindex 后的数据切分能力。
    """
    grouped: Dict[str, List[Dict[str, Any]]] = {}
    for shard in shards:
        if str(shard.get("prirep", "")).lower() != "p":
            continue
        grouped.setdefault(shard.get("index", ""), []).append(shard)

    result = {}
    for index, items in grouped.items():
        stores = [parse_int(item.get("store")) for item in items]
        docs = [parse_int(item.get("docs")) for item in items]
        result[index] = {
            "primary_shards": len(items),
            "primary_store_bytes_total": sum(stores),
            "primary_store_bytes_avg": statistics.mean(stores) if stores else 0,
            "primary_store_bytes_max": max(stores) if stores else 0,
            "primary_store_bytes_min": min(stores) if stores else 0,
            "primary_docs_total": sum(docs),
            "primary_docs_avg": statistics.mean(docs) if docs else 0,
            "primary_docs_max": max(docs) if docs else 0,
        }
    return result


def recommend_shards(primary_store_bytes: int, docs_count: int, sample: Dict[str, Any]) -> Dict[str, Any]:
    """
    业务功能：基于容量目标给出初始 shard 建议。
    关键流程：向量索引更怕超大 shard，默认按 50GB 目标估算，同时输出 30GB/80GB 边界用于人工取舍。
    """
    targets = {
        "conservative_30gb": 30 * 1024**3,
        "balanced_50gb": 50 * 1024**3,
        "upper_80gb": 80 * 1024**3,
    }
    per_chunk = primary_store_bytes / docs_count if docs_count else None
    return {
        "current_avg_store_per_doc_bytes": per_chunk,
        "recommended_primary_shards": {
            name: max(1, int((primary_store_bytes + target - 1) // target))
            for name, target in targets.items()
        },
        "scale_projection": build_scale_projection(per_chunk),
        "sample_source_bytes": sample.get("source_bytes", {}),
    }


def build_scale_projection(avg_bytes_per_doc: Optional[float]) -> List[Dict[str, Any]]:
    """
    业务功能：按当前平均存储成本推算更大 chunk 数下的容量。
    关键流程：把 5000万/1亿/2亿写入报告，方便离线规划磁盘和 replica。
    """
    if not avg_bytes_per_doc:
        return []
    rows = []
    for docs in SIZE_TARGETS:
        primary = avg_bytes_per_doc * docs
        rows.append(
            {
                "docs": docs,
                "primary_store_bytes": primary,
                "primary_store_human": bytes_to_human(primary),
                "with_replica_1_bytes": primary * 2,
                "with_replica_1_human": bytes_to_human(primary * 2),
            }
        )
    return rows


def write_csv(path: Path, rows: List[Dict[str, Any]]) -> None:
    """
    业务功能：输出 CSV 明细。
    关键流程：字段集合来自所有行，避免不同 ES 版本 cat API 字段差异导致列缺失。
    """
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fields = sorted({key for row in rows for key in row.keys()})
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(path: Path, report: Dict[str, Any]) -> None:
    """
    业务功能：输出适合人工阅读的 Markdown 报告。
    关键流程：只展示决策相关摘要，完整数据保留在 JSON/CSV 中。
    """
    lines = []
    lines.append("# Elasticsearch 容量审计报告")
    lines.append("")
    lines.append(f"- 生成时间: {report['generated_at']}")
    lines.append(f"- ES URL: {report['es_url']}")
    lines.append(f"- 索引模式: `{report['index_pattern']}`")
    lines.append(f"- 抽样大小: {report['sample_size']}")
    lines.append("")
    health = report.get("cluster_health", {})
    lines.append("## 集群概况")
    lines.append("")
    lines.append(f"- status: `{health.get('status', '-')}`")
    lines.append(f"- nodes: `{health.get('number_of_nodes', '-')}`")
    lines.append(f"- data_nodes: `{health.get('number_of_data_nodes', '-')}`")
    lines.append(f"- active_primary_shards: `{health.get('active_primary_shards', '-')}`")
    lines.append(f"- unassigned_shards: `{health.get('unassigned_shards', '-')}`")
    lines.append("")
    lines.append("## 索引摘要")
    lines.append("")
    lines.append("| index | docs | pri | rep | primary store | total store | avg/doc | shard suggestion |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---|")
    for item in report["indices"]:
        rec = report["recommendations"].get(item["index"], {})
        avg_doc = rec.get("current_avg_store_per_doc_bytes")
        suggest = rec.get("recommended_primary_shards", {})
        lines.append(
            "| {index} | {docs} | {pri} | {rep} | {pri_store} | {store} | {avg_doc} | {suggest} |".format(
                index=item.get("index"),
                docs=item.get("docs.count"),
                pri=item.get("pri"),
                rep=item.get("rep"),
                pri_store=bytes_to_human(item.get("pri.store.size")),
                store=bytes_to_human(item.get("store.size")),
                avg_doc=bytes_to_human(avg_doc),
                suggest=", ".join(f"{k}:{v}" for k, v in suggest.items()),
            )
        )
    lines.append("")
    lines.append("## Mapping 重点")
    lines.append("")
    for index, summary in report["mapping_summary"].items():
        lines.append(f"### {index}")
        lines.append("")
        lines.append(f"- field_count: `{summary.get('field_count')}`")
        lines.append(f"- vector_fields: `{json.dumps(summary.get('vector_fields', []), ensure_ascii=False)}`")
        lines.append(f"- permission_fields: `{json.dumps(summary.get('permission_fields', {}), ensure_ascii=False)}`")
        lines.append("")
    lines.append("## 抽样字段大小")
    lines.append("")
    for index, sample in report["samples"].items():
        lines.append(f"### {index}")
        lines.append("")
        lines.append(f"- sampled: `{sample.get('sampled')}`")
        lines.append(f"- _source bytes: `{json.dumps(sample.get('source_bytes', {}), ensure_ascii=False)}`")
        lines.append(f"- text bytes: `{json.dumps(sample.get('text_bytes', {}), ensure_ascii=False)}`")
        lines.append(f"- vector dims seen: `{json.dumps(sample.get('vector_dims_seen', {}), ensure_ascii=False)}`")
        lines.append("")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_report(es: EsClient, es_url: str, pattern: str, sample_size: int) -> Dict[str, Any]:
    """
    业务功能：编排完整审计流程。
    关键流程：先取集群和 cat 明细，再逐索引取 settings/mapping/sample，最后生成容量建议。
    """
    health = es.request("GET", "/_cluster/health")
    nodes_stats = es.request("GET", "/_nodes/stats/fs,jvm,os,process,indices")
    indices = collect_indices(es, pattern)
    shards = collect_shards(es, pattern)
    shard_stats = shard_summary(shards)
    settings = {}
    mapping_summary = {}
    samples = {}
    recommendations = {}

    for item in indices:
        index = item["index"]
        settings[index] = es.request("GET", f"/{urllib.parse.quote(index)}/_settings")
        mapping = es.request("GET", f"/{urllib.parse.quote(index)}/_mapping")
        mapping_summary[index] = extract_mapping_summary(mapping, index)
        samples[index] = sample_index(es, index, sample_size)
        recommendations[index] = recommend_shards(
            item.get("pri.store.size", 0),
            item.get("docs.count", 0),
            samples[index],
        )

    return {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S %z"),
        "es_url": es_url,
        "index_pattern": pattern,
        "sample_size": sample_size,
        "cluster_health": health,
        "nodes_stats": nodes_stats,
        "indices": indices,
        "shards": shards,
        "shard_summary": shard_stats,
        "settings": settings,
        "mapping_summary": mapping_summary,
        "samples": samples,
        "recommendations": recommendations,
    }


def main() -> int:
    """
    业务功能：命令行入口。
    关键流程：从环境变量读取连接和输出配置，运行审计后落盘报告，方便离线环境自由调整参数。
    """
    es_url = os.getenv("ES_URL", "http://localhost:9200")
    username = os.getenv("ES_USER", "")
    password = os.getenv("ES_PASSWORD", "")
    pattern = os.getenv("INDEX_PATTERN", DEFAULT_INDEX_PATTERN)
    output_dir = Path(os.getenv("OUTPUT_DIR", DEFAULT_OUTPUT_DIR))
    sample_size = env_int("SAMPLE_SIZE", DEFAULT_SAMPLE_SIZE, min_value=0)
    timeout = env_int("ES_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS, min_value=1)

    output_dir.mkdir(parents=True, exist_ok=True)
    es = EsClient(es_url, username, password, timeout)
    report = build_report(es, es_url, pattern, sample_size)

    json_path = output_dir / "es_capacity_report.json"
    md_path = output_dir / "es_capacity_report.md"
    indices_csv = output_dir / "es_indices.csv"
    shards_csv = output_dir / "es_shards.csv"

    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(md_path, report)
    write_csv(indices_csv, report["indices"])
    write_csv(shards_csv, report["shards"])

    print(f"[OK] JSON report: {json_path}")
    print(f"[OK] Markdown report: {md_path}")
    print(f"[OK] Indices CSV: {indices_csv}")
    print(f"[OK] Shards CSV: {shards_csv}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        raise SystemExit(1)
