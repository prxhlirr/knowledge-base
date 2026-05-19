"""
Phase 3 - 批量文档向量生成脚本 (batch_doc_vector_generator.py)

业务功能：
  为知识库中所有文档计算「文档级向量」(doc_vector)，存入新索引 kb_doc_meta。
  doc_vector = 该文档所有 fine chunk 向量的均值池化 (Mean Pooling)，
  是文档整体语义的压缩表示，用于「相似文档搜索」功能。

核心原理：
  多个 chunk 向量均值池化 → 消除单一 chunk 的局部偏差 → 得到能代表整篇文档的语义指纹。
  在 KNN 向量空间中，相似主题的文档 doc_vector 余弦相似度通常 > 0.75。

运行特性：
  - 幂等：以 metadata.source (文件名) 为主键写入 kb_doc_meta，重复运行只会更新
  - 断点续跑：通过 --skip-existing 参数跳过已生成 doc_vector 的文档
  - 无外部依赖：直接调用 ES REST API，不依赖 elasticsearch-py 的高级功能

用法：
  python batch_doc_vector_generator.py                  # 处理全部文档
  python batch_doc_vector_generator.py --skip-existing  # 跳过已处理文档
  python batch_doc_vector_generator.py --dry-run        # 仅统计，不写入
"""

import json
import time
import argparse
import urllib.request
import numpy as np

# ─────────────── 配置 ────────────────────────────────────────────────────────
ES_HOST      = "http://localhost:9200"
SOURCE_INDEX = "kb_document_v1"
META_INDEX   = "kb_doc_meta"
VECTOR_DIM   = 1024
PAGE_SIZE    = 100   # 每次 scroll 取 100 条 (避免超内存)
SLEEP_SECS   = 0.3  # 批间冷却

# ─────────────── kb_doc_meta 索引 Mapping ────────────────────────────────────
DOC_META_MAPPING = {
    "mappings": {
        "properties": {
            "source":       {"type": "keyword"},
            "data_source":  {"type": "keyword"},
            "chunk_count":  {"type": "integer"},
            "doc_vector":   {
                "type": "dense_vector",
                "dims": VECTOR_DIM,
                "index": True,
                "similarity": "cosine"
            },
            "updated_at":   {"type": "date", "format": "epoch_millis"}
        }
    }
}


def es_request(method: str, path: str, body=None):
    """通用 ES REST 请求封装"""
    url = ES_HOST + path
    data = json.dumps(body).encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data,
                                  headers={"Content-Type": "application/json"},
                                  method=method)
    try:
        resp = urllib.request.urlopen(req, timeout=30)
        return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8"))


def ensure_meta_index():
    """确保 kb_doc_meta 索引存在，不存在时创建。"""
    resp = es_request("HEAD", f"/{META_INDEX}")
    if isinstance(resp, dict) and resp.get("status") == 404:
        es_request("PUT", f"/{META_INDEX}", DOC_META_MAPPING)
        print(f"✅ [Index] Created: {META_INDEX}")
    else:
        # HEAD 请求成功时返回空 dict
        print(f"ℹ️  [Index] Already exists: {META_INDEX}")


def ensure_meta_index_v2():
    """更健壮的索引检查（通过 GET 而非 HEAD）"""
    resp = es_request("GET", f"/{META_INDEX}/_settings")
    if "error" in resp:
        es_request("PUT", f"/{META_INDEX}", DOC_META_MAPPING)
        print(f"✅ [Index] Created: {META_INDEX}")
    else:
        print(f"ℹ️  [Index] Already exists: {META_INDEX}")


def get_existing_sources() -> set:
    """获取 kb_doc_meta 中已有的 source 集合，用于断点续跑。"""
    existing = set()
    from_pos = 0
    while True:
        resp = es_request("POST", f"/{META_INDEX}/_search", {
            "from": from_pos,
            "size": 500,
            "_source": ["source"],
            "query": {"match_all": {}}
        })
        hits = resp.get("hits", {}).get("hits", [])
        if not hits:
            break
        for h in hits:
            src = h.get("_source", {}).get("source")
            if src:
                existing.add(src)
        from_pos += len(hits)
        if len(hits) < 500:
            break
    return existing


def fetch_all_fine_chunks() -> dict:
    """
    从 kb_document_v1 获取所有 fine chunk 的 source + vector。
    返回：{ source_filename -> { "data_source": str, "vectors": [[1024 floats], ...] } }
    使用简单分页而非 scan，避免 elasticsearch-py 版本兼容问题。
    """
    doc_groups = {}
    from_pos = 0
    total_chunks = 0

    query = {
        "query": {
            "term": {"chunk_granularity": "fine"}
        },
        "_source": ["vector", "metadata.source", "metadata.data_source"],
        "size": PAGE_SIZE
    }

    print(f"📡 [Fetch] Paging through {SOURCE_INDEX} (fine chunks)...")

    while True:
        query["from"] = from_pos
        resp = es_request("POST", f"/{SOURCE_INDEX}/_search", query)
        hits = resp.get("hits", {}).get("hits", [])

        if not hits:
            break

        for hit in hits:
            src = hit.get("_source", {})
            meta = src.get("metadata", {})
            source = meta.get("source", "")
            data_source = meta.get("data_source", "default")
            vector = src.get("vector")

            if not source or not vector:
                continue

            if source not in doc_groups:
                doc_groups[source] = {
                    "data_source": data_source,
                    "vectors": []
                }
            doc_groups[source]["vectors"].append(vector)
            total_chunks += 1

        from_pos += len(hits)
        if len(hits) < PAGE_SIZE:
            break

    print(f"✅ [Fetch] {total_chunks} fine chunks → {len(doc_groups)} unique documents")
    return doc_groups


def compute_doc_vector(vectors: list) -> list:
    """
    对文档的所有 fine chunk 向量做均值池化，得到 L2 归一化的文档级向量。
    均值池化比最大池化更能捕捉文档整体语义，比单一 chunk 更稳健。
    """
    arr = np.array(vectors, dtype=np.float32)
    mean_vec = np.mean(arr, axis=0)
    norm = np.linalg.norm(mean_vec)
    if norm > 0:
        mean_vec = mean_vec / norm
    return mean_vec.tolist()


def write_doc_meta(source: str, doc_info: dict, doc_vector: list):
    """将 doc_vector 写入 kb_doc_meta。以 source 为 _id（URL 编码），幂等。"""
    import urllib.parse
    doc_id = urllib.parse.quote(source, safe="")
    body = {
        "source":      source,
        "data_source": doc_info["data_source"],
        "chunk_count": len(doc_info["vectors"]),
        "doc_vector":  doc_vector,
        "updated_at":  int(time.time() * 1000)
    }
    resp = es_request("PUT", f"/{META_INDEX}/_doc/{doc_id}", body)
    if "error" in resp:
        raise RuntimeError(str(resp["error"]))


def run(skip_existing: bool = False, dry_run: bool = False):
    """主处理流程。"""
    if not dry_run:
        ensure_meta_index_v2()

    existing_sources = set()
    if skip_existing and not dry_run:
        existing_sources = get_existing_sources()
        print(f"⏭️  [Skip] {len(existing_sources)} documents already processed.")

    doc_groups = fetch_all_fine_chunks()

    total = len(doc_groups)
    skipped = success = failed = 0

    print(f"\n🚀 [Process] Generating doc_vector for {total} documents...\n")

    for i, (source, doc_info) in enumerate(doc_groups.items()):
        if source in existing_sources:
            skipped += 1
            continue

        print(f"[{i+1}/{total}] {source} ({len(doc_info['vectors'])} chunks)", end=" → ")

        if dry_run:
            print("DRY RUN (skip write)")
            continue

        try:
            doc_vector = compute_doc_vector(doc_info["vectors"])
            write_doc_meta(source, doc_info, doc_vector)
            success += 1
            print("✅")
        except Exception as e:
            failed += 1
            print(f"❌ {e}")

        if (i + 1) % 5 == 0 and i + 1 < total:
            time.sleep(SLEEP_SECS)

    print(f"\n📊 [Result] Total={total} | Success={success} | Skipped={skipped} | Failed={failed}")
    if not dry_run and success > 0:
        # 刷新索引确保搜索可见
        es_request("POST", f"/{META_INDEX}/_refresh")
        print(f"✅ kb_doc_meta has {success + skipped} documents with doc_vector.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Phase 3: Batch doc_vector generator")
    parser.add_argument("--skip-existing", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    run(skip_existing=args.skip_existing, dry_run=args.dry_run)
