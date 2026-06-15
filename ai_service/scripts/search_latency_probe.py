"""
Lightweight latency probe for /api/v1/search and /api/v1/search/home.

Examples:
  python ai_service/scripts/search_latency_probe.py --url http://localhost:8080 --query "任职 公示" --mode keyword -n 50 -c 5
  python ai_service/scripts/search_latency_probe.py --url http://localhost:8080 --queries-file queries.txt --endpoint /api/v1/search/home -n 100 -c 10
"""

import argparse
import concurrent.futures
import json
import statistics
import time
import urllib.error
import urllib.request


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = int(round((pct / 100.0) * (len(ordered) - 1)))
    return ordered[max(0, min(idx, len(ordered) - 1))]


def load_queries(args):
    if args.queries_file:
        with open(args.queries_file, "r", encoding="utf-8") as fh:
            queries = [line.strip() for line in fh if line.strip()]
        if queries:
            return queries
    return [args.query]


def run_once(base_url, endpoint, query, mode, page_size, app_code, token, timeout):
    payload = {
        "queryText": query,
        "searchMode": mode,
        "pageNum": 1,
        "pageSize": page_size,
        "filters": {},
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "X-Search-AppCode": app_code,
    }
    if token:
        headers["Authorization"] = token if token.lower().startswith("bearer ") else f"Bearer {token}"
    req = urllib.request.Request(base_url.rstrip("/") + endpoint, data=data, headers=headers, method="POST")
    start = time.perf_counter()
    status = 0
    size = 0
    error = ""
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.status
            body = resp.read()
            size = len(body)
            if status >= 400:
                error = body[:200].decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        status = exc.code
        error = exc.read()[:200].decode("utf-8", errors="replace")
    except Exception as exc:
        error = str(exc)
    elapsed_ms = (time.perf_counter() - start) * 1000.0
    return {
        "latency_ms": elapsed_ms,
        "status": status,
        "ok": 200 <= status < 300 and not error,
        "bytes": size,
        "error": error,
        "query": query,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--endpoint", default="/api/v1/search")
    parser.add_argument("--query", default="任职 公示")
    parser.add_argument("--queries-file")
    parser.add_argument("--mode", default="keyword", choices=["keyword", "hybrid", "semantic"])
    parser.add_argument("-n", "--requests", type=int, default=20)
    parser.add_argument("-c", "--concurrency", type=int, default=4)
    parser.add_argument("--page-size", type=int, default=10)
    parser.add_argument("--app-code", default="boyang-kb")
    parser.add_argument("--token", default="")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    queries = load_queries(args)
    results = []
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = []
        for i in range(args.requests):
            query = queries[i % len(queries)]
            futures.append(pool.submit(
                run_once,
                args.url,
                args.endpoint,
                query,
                args.mode,
                args.page_size,
                args.app_code,
                args.token,
                args.timeout,
            ))
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            marker = "ok" if result["ok"] else "err"
            print(f"{marker} {result['latency_ms']:.1f}ms status={result['status']} bytes={result['bytes']} query={result['query']}")

    latencies = [r["latency_ms"] for r in results if r["ok"]]
    errors = [r for r in results if not r["ok"]]
    wall_ms = (time.perf_counter() - started) * 1000.0
    summary = {
        "requests": len(results),
        "ok": len(latencies),
        "errors": len(errors),
        "wall_ms": round(wall_ms, 1),
        "qps": round((len(results) * 1000.0 / wall_ms), 2) if wall_ms > 0 else 0,
        "avg_ms": round(statistics.mean(latencies), 1) if latencies else 0,
        "p50_ms": round(percentile(latencies, 50), 1),
        "p90_ms": round(percentile(latencies, 90), 1),
        "p95_ms": round(percentile(latencies, 95), 1),
        "p99_ms": round(percentile(latencies, 99), 1),
        "max_ms": round(max(latencies), 1) if latencies else 0,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if errors:
        print("sample_errors:")
        for err in errors[:5]:
            print(json.dumps(err, ensure_ascii=False))


if __name__ == "__main__":
    main()
