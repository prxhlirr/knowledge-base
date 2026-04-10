import os
from elasticsearch import Elasticsearch

# 连接 ES
es_host = os.environ.get("ES_HOST", "http://localhost:9200")
es = Elasticsearch(hosts=[es_host])

template_name = "kb_template"

# 定义 Index Template (匹配模式为 kb_*)
# 这里在 metadata 映射中预定义 data_source, owner_dept_id, visible_depts
template_body = {
    "index_patterns": ["kb_*"],
    "template": {
        "mappings": {
            "properties": {
                "content": {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "fields": {
                        "keyword": {
                            "type": "keyword",
                            "ignore_above": 256
                        }
                    }
                },
                "doc_title": {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "fields": {
                        "keyword": {
                            "type": "keyword"
                        }
                    }
                },
                "vector": {
                    "type": "dense_vector",
                    "dims": 1024,
                    "index": True,
                    "similarity": "cosine",
                    "index_options": {
                        "type": "hnsw",
                        "m": 16,
                        "ef_construction": 100
                    }
                },
                # --------- 统一元数据字段规范 ---------
                "source": { "type": "keyword" },
                "chunk_id": { "type": "integer" },
                "is_latest": { "type": "boolean" },
                "data_source": { "type": "keyword" },
                "owner_dept_id": { "type": "keyword" },
                "visible_depts": { "type": "keyword" }
            }
        }
    }
}

try:
    if es.indices.exists_index_template(name=template_name):
        es.indices.delete_index_template(name=template_name)
    
    es.indices.put_index_template(
        name=template_name,
        index_patterns=["kb_*"],
        template={
            "mappings": template_body["template"]["mappings"]
        }
    )
    print(f"✅ 成功注册 Index Template: '{template_name}'，将匹配所有 'kb_*' 索引。")
except Exception as e:
    print(f"❌ 注册失败: {e}")
