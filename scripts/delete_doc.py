from elasticsearch import Elasticsearch

client = Elasticsearch("http://localhost:9200")
index_name = "bge_m3_index"

# 构造删除查询，匹配 metadata.source 等于特定的文件名
query = {
    "query": {
        "match": {
            "metadata.source.keyword": "~$文档_1_doc.doc" 
        }
    }
}

response = client.delete_by_query(index=index_name, body=query)
print(f"成功删除了 {response['deleted']} 个分片文档")
