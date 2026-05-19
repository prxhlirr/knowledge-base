import os
from elasticsearch import Elasticsearch

# Elasticsearch 连接配置
ES_HOST = "http://localhost:9200"
INDEX_NAME = "bge_m3_index"

def setup_es_index():
    # 初始化客户端 (本地模拟建议禁用安全认证以便调试)
    client = Elasticsearch(ES_HOST)
    
    # 检查索引是否存在，存在则删除（本地模拟环境使用）
    if client.indices.exists(index=INDEX_NAME):
        client.indices.delete(index=INDEX_NAME)
        print(f"已删除旧索引: {INDEX_NAME}")

    # 定义索引映射 (Mapping)
    mappings = {
        "properties": {
            "content": {
                "type": "text",
                "analyzer": "ik_max_word", # 建议安装 IK 分词器，否则使用 standard
                "fields": {
                    "keyword": {
                        "type": "keyword",
                        "ignore_above": 256
                    }
                }
            },
            "vector": {
                "type": "dense_vector",
                "dims": 1024, # BGE-m3 的稠密向量维度
                "index": True,
                "similarity": "cosine", # 使用余弦相似度
                "index_options": {
                    "type": "hnsw", # 使用 HNSW 算法
                    "m": 16,
                    "ef_construction": 100
                }
            },
            "metadata": {
                "properties": {
                    "source": {"type": "keyword"},
                    "page": {"type": "integer"}
                }
            }
        }
    }

    # 创建索引
    client.indices.create(index=INDEX_NAME, mappings=mappings)
    print(f"成功创建 ES 8.6 索引: {INDEX_NAME}")

if __name__ == "__main__":
    setup_es_index()
