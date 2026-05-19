"""
批量 colloquial_vector 回填脚本
业务功能：扫描 kb_document_v1 中所有 fine chunk，找出尚无 colloquial_vector 字段的 chunk，
         调用 AI Service /api/ai/colloquial/generate 生成口语化搜索短语，
         对短语向量化取均值后，更新回 ES 的 colloquial_vector 字段。
使用方式：python scripts/batch_colloquial_generator.py
         可选参数：--dry-run   只统计缺口，不实际生成
                  --limit N   最多处理 N 个 chunk
                  --sleep F   每个 chunk 生成后限流间隔（秒，默认 1.5）
关键设计：
  1. 幂等性：先用 scroll 扫描已有 colloquial_vector 的 chunk，跳过已处理的
  2. 限流：每个 chunk 生成后 sleep N 秒，避免 Qwen 7B 过载
  3. 断点续跑：随时 Ctrl+C 中断后重新运行（已生成的不会重复）
  4. 就地更新：通过 ES update_by_query / update(doc={...}) 局部更新，不重索引整个文档
"""

import os
import sys
import time
import argparse
import requests
import numpy as np
from elasticsearch import Elasticsearch

# ── 配置 ────────────────────────────────────────────────────────────────────
ES_HOST       = os.getenv("ES_HOST", "http://localhost:9200")
AI_HOST       = os.getenv("AI_SERVICE_HOST", "http://127.0.0.1:8001")
DOC_INDEX     = "kb_document_v1"
MIN_CHUNK_LEN = 15   # 少于此字数的 chunk 跳过生成
BATCH_SLEEP_S = 1.5  # 每个 chunk 生成后的限流间隔（秒）
# ────────────────────────────────────────────────────────────────────────────


def fetch_fine_chunks_missing_colloquial(es: Elasticsearch, limit: int = None):
    """
    分页拉取所有 is_latest=true 的 fine chunk，过滤掉已有 colloquial_vector 的 chunk。
    返回 [(chunk_id, content)] 列表。
    """
    result = []
    body = {
        "_source": ["content"],
        "query": {
            "bool": {
                "must": [
                    {"term": {"chunk_granularity": "fine"}},
                    {"term": {"metadata.is_latest": True}}
                ],
                # must_not: 已有 colloquial_vector 的跳过（filter exists 反转）
                "must_not": [
                    {"exists": {"field": "colloquial_vector"}}
                ]
            }
        },
        "size": 200
    }
    page = es.search(index=DOC_INDEX, body=body, scroll="5m")
    sid  = page["_scroll_id"]
    hits = page["hits"]["hits"]
    while hits:
        for h in hits:
            content = (h["_source"].get("content") or "").strip()
            if len(content) >= MIN_CHUNK_LEN:
                result.append((h["_id"], content))
            if limit and len(result) >= limit:
                es.clear_scroll(scroll_id=sid)
                return result
        page = es.scroll(scroll_id=sid, scroll="5m")
        sid  = page["_scroll_id"]
        hits = page["hits"]["hits"]
    es.clear_scroll(scroll_id=sid)
    return result


def generate_colloquial_phrases(content: str) -> list:
    """
    调用 /api/ai/colloquial/generate 为单个 chunk 生成口语化搜索短语列表。
    返回短语字符串列表，超时或失败时返回空列表。
    """
    try:
        resp = requests.post(
            f"{AI_HOST}/api/ai/colloquial/generate",
            json={"text": content},
            timeout=60.0
        )
        if resp.status_code == 200:
            return resp.json().get("data", [])
    except Exception as e:
        print(f"  ⚠️ colloquial/generate 失败: {e}")
    return []


def encode_phrase(phrase: str) -> list:
    """
    调用 /api/ai/vector/query 对短语文本向量化（BGE-M3）。
    返回 1024 维向量列表，失败时返回 None。
    """
    try:
        resp = requests.post(
            f"{AI_HOST}/api/ai/vector/query",
            json={"text": phrase},
            timeout=15.0
        )
        if resp.status_code == 200:
            return resp.json().get("data", {}).get("vector")
    except Exception as e:
        print(f"  ⚠️ vector/query 失败: {e}")
    return None


def update_chunk_colloquial_vector(es: Elasticsearch, chunk_id: str, c_vec: list) -> bool:
    """
    就地更新 ES 文档的 colloquial_vector 字段（不重建整个文档）。
    """
    try:
        es.update(
            index=DOC_INDEX,
            id=chunk_id,
            body={"doc": {"colloquial_vector": c_vec}}
        )
        return True
    except Exception as e:
        print(f"  ❌ ES update 失败 ({chunk_id}): {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description="批量回填 colloquial_vector")
    parser.add_argument("--dry-run", action="store_true", help="只统计缺口，不生成")
    parser.add_argument("--limit",  type=int,   default=None, help="最多处理 N 个 chunk")
    parser.add_argument("--sleep",  type=float, default=BATCH_SLEEP_S, help="每 chunk 完成后的限流间隔(秒)")
    args = parser.parse_args()

    es = Elasticsearch(ES_HOST)
    if not es.ping():
        print("❌ 无法连接 Elasticsearch")
        sys.exit(1)
    print(f"✅ ES 连接成功：{ES_HOST}")

    # 1. 拉取所有缺少 colloquial_vector 的 fine chunk
    print("\n📖 扫描尚未有 colloquial_vector 的 fine chunk（这可能需要几秒钟）...")
    missing = fetch_fine_chunks_missing_colloquial(es, limit=args.limit)
    print(f"  需要补全的 chunk 数：{len(missing)}")

    if not missing:
        print("\n🎉 所有 fine chunk 已有 colloquial_vector，无需补全！")
        return

    if args.dry_run:
        print(f"\n[dry-run] 前 10 条缺失 chunk：")
        for cid, content in missing[:10]:
            print(f"  - {cid}: '{content[:50]}...'")
        return

    # 2. 批量生成
    print(f"\n🚀 开始批量生成（限流间隔 {args.sleep}s/chunk）...\n")
    total_ok      = 0
    total_failed  = 0
    total_skipped = 0

    for i, (chunk_id, content) in enumerate(missing, 1):
        print(f"[{i}/{len(missing)}] chunk: {chunk_id[:40]}...")

        phrases = generate_colloquial_phrases(content)
        if not phrases:
            print("  ⚠️ 未生成任何短语，跳过")
            total_failed += 1
        else:
            print(f"  短语: {phrases}")
            # 对所有短语向量化取均值
            vecs = [encode_phrase(p) for p in phrases]
            valid_vecs = [v for v in vecs if v is not None]
            if not valid_vecs:
                print("  ⚠️ 向量化全部失败，跳过")
                total_skipped += 1
            else:
                c_vec = np.mean(np.array(valid_vecs), axis=0).tolist()
                if update_chunk_colloquial_vector(es, chunk_id, c_vec):
                    total_ok += 1
                    print(f"  ✅ 写入 colloquial_vector（由 {len(valid_vecs)} 个短语均值生成）")
                else:
                    total_failed += 1

        # 进度打印
        if i % 10 == 0:
            print(f"\n{'='*50}")
            print(f"  进度 {i}/{len(missing)} | 成功: {total_ok} | 失败: {total_failed}")
            print(f"{'='*50}\n")

        time.sleep(args.sleep)

    print(f"\n{'='*50}")
    print(f"✅ 批量回填完成！")
    print(f"  总处理 chunk: {len(missing)}")
    print(f"  写入成功: {total_ok}")
    print(f"  向量化失败(跳过): {total_skipped}")
    print(f"  其他失败: {total_failed}")
    print(f"{'='*50}")


if __name__ == "__main__":
    main()
