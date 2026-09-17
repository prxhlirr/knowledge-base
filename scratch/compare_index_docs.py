import json
from elasticsearch import Elasticsearch

def main():
    es = Elasticsearch("http://localhost:9200")
    
    # 1. 扫描 kb_document_official 中的所有文档
    print("========== 扫描 kb_document_official 中的分片信息 ==========")
    physical_docs = {}
    
    # 使用 Scroll 获取所有分片以统计文档
    try:
        res = es.search(
            index="kb_document_official",
            scroll="2m",
            size=1000,
            _source=["metadata.source", "metadata.doc_id", "content_hash"]
        )
        
        scroll_id = res["_scroll_id"]
        hits = res["hits"]["hits"]
        
        while hits:
            for hit in hits:
                src = hit.get("_source", {})
                meta = src.get("metadata", {})
                
                # 获取文件名和 doc_id
                file_name = meta.get("source") or src.get("source") or ""
                doc_id = meta.get("doc_id") or src.get("doc_id") or src.get("content_hash") or ""
                
                if not doc_id:
                    # 从 _id 还原
                    _id = hit["_id"]
                    if "_" in _id:
                        doc_id = _id.split("_")[0]
                
                if file_name:
                    if file_name not in physical_docs:
                        physical_docs[file_name] = {
                            "doc_ids": set(),
                            "chunk_count": 0
                        }
                    physical_docs[file_name]["chunk_count"] += 1
                    if doc_id:
                        physical_docs[file_name]["doc_ids"].add(doc_id)
            
            res = es.scroll(scroll_id=scroll_id, scroll="2m")
            scroll_id = res["_scroll_id"]
            hits = res["hits"]["hits"]
            
        es.clear_scroll(scroll_id=scroll_id)
    except Exception as e:
        print("读取物理分片索引报错:", e)
        return

    print(f"物理分片索引中共有 {len(physical_docs)} 个不同的文件：")
    for fname, info in physical_docs.items():
        print(f"  - 文件名: {fname} | 分片数: {info['chunk_count']} | 提取到的 doc_id 集合: {list(info['doc_ids'])}")

    # 2. 扫描 kb_doc_search_v1 中的所有文档
    print("\n========== 扫描 kb_doc_search_v1 中的文档信息 ==========")
    search_docs = {}
    try:
        res = es.search(
            index="kb_doc_search_v1",
            size=1000,
            _source=["doc_id", "source", "source_name", "content_hash"]
        )
        hits = res["hits"]["hits"]
        for hit in hits:
            src = hit.get("_source", {})
            file_name = src.get("source") or src.get("source_name") or ""
            doc_id = src.get("doc_id") or hit["_id"]
            content_hash = src.get("content_hash") or ""
            
            if file_name:
                search_docs[file_name] = {
                    "doc_id": doc_id,
                    "content_hash": content_hash,
                    "es_id": hit["_id"]
                }
    except Exception as e:
        print("读取文档级索引报错:", e)
        return

    print(f"文档级索引中共有 {len(search_docs)} 个文档。")
    for fname, info in search_docs.items():
        print(f"  - 文件名: {fname} | doc_id: {info['doc_id']} | content_hash: {info['content_hash']}")

    # 3. 对比分析
    print("\n========== 对比分析一致性 ==========")
    missing_in_search = []
    mismatched_fields = []
    
    for fname, p_info in physical_docs.items():
        if fname not in search_docs:
            missing_in_search.append(fname)
        else:
            s_info = search_docs[fname]
            p_doc_ids = list(p_info["doc_ids"])
            if p_doc_ids:
                p_doc_id = p_doc_ids[0]
                if s_info["doc_id"] != p_doc_id or s_info["content_hash"] != p_doc_id:
                    mismatched_fields.append({
                        "file_name": fname,
                        "physical_doc_id": p_doc_id,
                        "search_doc_id": s_info["doc_id"],
                        "search_content_hash": s_info["content_hash"]
                    })

    print(f"1. 在物理索引中存在但 kb_doc_search 中缺失的文件数量: {len(missing_in_search)}")
    if missing_in_search:
        print("   缺失文件列表:")
        for m in missing_in_search:
            print(f"     * {m}")
            
    print(f"2. 存在但 ID/Hash 不匹配的文件数量: {len(mismatched_fields)}")
    if mismatched_fields:
        print("   不匹配详情:")
        for item in mismatched_fields:
            print(f"     * 文件: {item['file_name']} | 物理 ID: {item['physical_doc_id']} | 检索 ID: {item['search_doc_id']} | 检索 Hash: {item['search_content_hash']}")

    if not missing_in_search and not mismatched_fields:
        print("\n结论: 物理索引与文档索引已完全对齐！")
    else:
        print("\n结论: 物理索引与文档索引未完全对齐。")

if __name__ == "__main__":
    main()
