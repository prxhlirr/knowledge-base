# -*- coding: utf-8 -*-
"""
业务功能：统一知识库 ES 索引字段离线对齐与高可靠补全脚本。
设计决策：针对亿级分片和百万级文档的数据量进行深度性能优化：
  1. 严格排除向量大字段（vector, colloquial_vector），极大地控制内存和网络带宽。
  2. 流式 Scroll 拉取轻量元数据，在内存中维护轻量级的 unique 文件元数据缓存，杜绝 OOM。
  3. 针对 kb_doc_search 使用 streaming_bulk 批量 Upsert 补全缺失和已有文档。
  4. 针对 kb_document* 物理索引使用 terms + Painless 脚本的 `_update_by_query` 批量合并更新，将百次更新压缩为一次，极大地减轻 ES 负担。
"""

import sys
import time
import re
import json
from elasticsearch import Elasticsearch, helpers

# 确保控制台输出使用 UTF-8
try:
    sys.stdout.reconfigure(encoding='utf-8')
except AttributeError:
    pass

# 文档级别主索引与物理分片索引的物理名
DOC_SEARCH_INDEX = "kb_doc_search_v1"
DOCUMENT_INDEX = "kb_document_official"

def extract_hash_from_id(es_id):
    """
    根据第一性原理，从分片 _id 中截取真实的文档 Hash (32位 MD5 值)
    例如：80c83ce0798c2b0674e915e761731f2e_v16_chunk_7 -> 80c83ce0798c2b0674e915e761731f2e
    """
    if not es_id:
        return ""
    v_idx = es_id.find("_v")
    if v_idx > 0:
        return es_id[:v_idx]
    chunk_idx = es_id.find("_chunk_")
    if chunk_idx > 0:
        return es_id[:chunk_idx]
    fine_idx = es_id.find("_fine_")
    if fine_idx > 0:
        return es_id[:fine_idx]
    first_underscore = es_id.find("_")
    if first_underscore > 0:
        return es_id[:first_underscore]
    return es_id

def scan_physical_metadata(es):
    """
    通过轻量级 Scroll 扫描物理分片索引，提取每个文件的 unique 哈希与元数据，
    在内存中只保存轻量结构，以确保亿级分片扫描不发生 OOM。
    """
    print("[1/3] 开始扫描物理分片索引以提取元数据...")
    docs_info = {}
    
    # 限制只返回轻量级元数据，排除向量和正文
    source_includes = [
        "metadata.source", "metadata.title", "metadata.doc_type", 
        "metadata.doc_version", "metadata.is_latest", "metadata.visibility", 
        "metadata.owner_dept_id", "metadata.acl_tokens", "metadata.version_at", 
        "metadata.document_number", "chunk_granularity", "keywords"
    ]
    
    # 采用 Scroll 批量拉取
    scroll_time = "10m"
    batch_size = 5000
    
    query = {
        "query": {"match_all": {}},
        "_source": source_includes,
        "size": batch_size
    }
    
    resp = es.search(index=DOCUMENT_INDEX, scroll=scroll_time, body=query)
    scroll_id = resp["_scroll_id"]
    hits = resp["hits"]["hits"]
    
    total_scanned = 0
    start_time = time.time()
    
    while hits:
        for hit in hits:
            total_scanned += 1
            src = hit.get("_source", {})
            meta = src.get("metadata", {})
            source_file = meta.get("source")
            
            # 过滤脏数据和缺失源名的分片
            if not source_file:
                continue
                
            es_id = hit["_id"]
            doc_id = extract_hash_from_id(es_id)
            if not doc_id:
                continue
                
            # 初始化本地聚合文档缓存项（只在第一次遇到文件时创建）
            if source_file not in docs_info:
                docs_info[source_file] = {
                    "doc_id": doc_id,
                    "title": meta.get("title", source_file),
                    "document_number": meta.get("document_number", ""),
                    "doc_type": meta.get("doc_type", "未知"),
                    "doc_version": meta.get("doc_version", 1),
                    "is_latest": meta.get("is_latest", True),
                    "visibility": meta.get("visibility", "INTERNAL"),
                    "owner_dept_id": meta.get("owner_dept_id", "global"),
                    "acl_tokens": meta.get("acl_tokens", ["_INTERNAL"]),
                    "updated_at": meta.get("version_at", int(time.time() * 1000)),
                    "chunk_count": 0,
                    "representative_chunk_ids": [],
                    "keywords": set()
                }
            
            doc_item = docs_info[source_file]
            
            # 统计 coarse 粗粒度分片以生成 chunk_count
            if src.get("chunk_granularity") == "coarse":
                doc_item["chunk_count"] += 1
                chunk_id = meta.get("chunk_id")
                if chunk_id is not None:
                    doc_item["representative_chunk_ids"].append(str(chunk_id))
            
            # 合并 keywords
            kws = src.get("keywords")
            if kws:
                if isinstance(kws, list):
                    doc_item["keywords"].update(kws)
                else:
                    doc_item["keywords"].add(str(kws))
                    
        if total_scanned % 100000 == 0:
            elapsed = time.time() - start_time
            print(f"  已扫描分片: {total_scanned}，当前内存中 unique 文档数: {len(docs_info)}，耗时: {elapsed:.2f}s")
            
        # 抓取下一批 scroll
        resp = es.scroll(scroll_id=scroll_id, scroll=scroll_time)
        scroll_id = resp.get("_scroll_id", scroll_id)
        hits = resp["hits"]["hits"]
        
    # 清理 ES 的 Scroll 资源
    try:
        es.clear_scroll(scroll_id=scroll_id)
    except Exception:
        pass
        
    print(f"扫描物理索引完成。累计分片数: {total_scanned}，去重后 unique 文档数: {len(docs_info)}")
    return docs_info

def upsert_doc_search_index(es, docs_info):
    """
    使用 streaming_bulk API 将提取的对齐元数据批量 upsert 写入 kb_doc_search_v1 索引，
    对于缺失的文件会以 default 属性直接补齐插入，从而在物理上保证两个索引的文件数量一致。
    """
    print("[2/3] 开始批量 Upsert 补全文档级主索引 (kb_doc_search_v1)...")
    
    actions = []
    for source_file, info in docs_info.items():
        doc_id = info["doc_id"]
        # 对列表进行去重并升序排序作为代表性 chunks 序列
        rep_chunks = sorted(list(set(info["representative_chunk_ids"])), key=lambda x: int(x) if x.isdigit() else 999)
        
        # 构造 upsert 数据包
        doc_data = {
            "doc_id": doc_id,
            "content_hash": doc_id,
            "source": source_file,
            "source_name": source_file,
            "title": info["title"],
            "doc_title": info["title"],
            "document_number": info["document_number"],
            "doc_type": info["doc_type"],
            "doc_version": info["doc_version"],
            "is_latest": info["is_latest"],
            "visibility": info["visibility"],
            "owner_dept_id": info["owner_dept_id"],
            "acl_tokens": list(info["acl_tokens"]),
            "updated_at": info["updated_at"],
            "chunk_count": info["chunk_count"],
            "representative_chunk_ids": rep_chunks,
            "keywords": list(info["keywords"]),
            "data_source": "document",
            "security_level": 0,
            "visible_depts": []
        }
        
        # 使用 update 动作，当文档不存在时以 doc_data 作 upsert 初始化，
        # 已存在时只增量更新关联 ID，从而安全地保护已有 vector 高维数据不被擦除
        action = {
            "_op_type": "update",
            "_index": DOC_SEARCH_INDEX,
            "_id": source_file, # 以文件名作为 _id
            "doc": {
                "doc_id": doc_id,
                "content_hash": doc_id
            },
            "upsert": doc_data
        }
        actions.append(action)
        
    success_count = 0
    fail_count = 0
    
    # 执行 bulk 批量刷盘
    for success, response in helpers.streaming_bulk(es, actions, chunk_size=1000):
        if success:
            success_count += 1
        else:
            fail_count += 1
            print(f"  更新失败文档: {response}")
            
    print(f"文档级主索引补全完毕。Upsert 成功数: {success_count}，失败数: {fail_count}")

def update_physical_doc_ids_batch(es, docs_info):
    """
    核心性能优化：利用 terms 查询加上 Painless 脚本的 _update_by_query API，
    在一轮请求中以 batch 形式批量将物理分片上的 metadata.doc_id 进行同步更新，
    此操作在 ES 内部并发处理，极大地减轻了亿级数据量下的网络 IO 负担。
    """
    print("[3/3] 开始批量更新物理索引分片 (kb_document_official) 中的 metadata.doc_id 字段...")
    
    # 限制单次 _update_by_query 批量的文件数，避免 payload 和 ES Painless 执行超载
    batch_limit = 200
    keys = list(docs_info.keys())
    total_docs = len(keys)
    
    painless_script = """
    def m = params.mapping;
    def src = ctx._source.metadata.source;
    if (m.containsKey(src)) {
        ctx._source.metadata.doc_id = m[src];
        ctx._source.content_hash = m[src];
    }
    """
    
    for i in range(0, total_docs, batch_limit):
        batch_keys = keys[i:i + batch_limit]
        mapping_param = {k: docs_info[k]["doc_id"] for k in batch_keys}
        
        # 批量 update_by_query 请求 DSL
        body = {
            "query": {
                "terms": {
                    "metadata.source": batch_keys
                }
            },
            "script": {
                "source": painless_script,
                "lang": "painless",
                "params": {
                    "mapping": mapping_param
                }
            }
        }
        
        print(f"  正在提交物理分片更新: {i + 1} ~ {min(i + batch_limit, total_docs)} / {total_docs} 文件...")
        try:
            # wait_for_completion=False 以异步任务模式执行，避免客户端超时
            task_resp = es.update_by_query(
                index=DOCUMENT_INDEX, 
                body=body, 
                conflicts="proceed", 
                wait_for_completion=False
            )
            task_id = task_resp.get("task")
            if task_id:
                # 本地打印 task ID 供离线环境性能监控与跟踪
                print(f"    异步任务已创建 TaskId: {task_id}")
        except Exception as e:
            print(f"    更新物理分片批次失败: {e}")
            
    print("全部物理分片异步更新任务提交完毕。请在 Kibana 或后台通过 GET /_tasks/<TaskId> 关注分片更新进度。")

def main():
    start_time = time.time()
    try:
        es = Elasticsearch("http://localhost:9200", timeout=60)
        
        # 1. 扫描物理索引提取 unique 元数据
        docs_info = scan_physical_metadata(es)
        if not docs_info:
            print("物理分片索引无有效数据，终止补全任务。")
            return
            
        # 2. Upsert 补全文档级搜索主索引，保证数量对准
        upsert_doc_search_index(es, docs_info)
        
        # 3. 批量异步对齐物理分片的 metadata.doc_id 
        update_physical_doc_ids_batch(es, docs_info)
        
        print(f"全链路字段补全与对齐初始化完成。耗时: {time.time() - start_time:.2f}s")
        
    except Exception as e:
        print(f"数据对准执行发生异常: {e}")

if __name__ == "__main__":
    main()
