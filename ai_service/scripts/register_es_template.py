import os
from elasticsearch import Elasticsearch


def env_int(name: str, default: int, min_value: int = 0) -> int:
    """
    业务功能：读取模板注册脚本的整数配置。
    关键流程：历史模板脚本可能在离线环境单独执行，集中解析能避免硬编码分片和 HNSW 参数。
    """
    raw = os.getenv(name)
    if raw is None or str(raw).strip() == "":
        return default
    try:
        value = int(str(raw).strip())
    except ValueError:
        print(f"[Template] env {name}={raw!r} is not an integer, fallback to {default}")
        return default
    if value < min_value:
        print(f"[Template] env {name}={value} is lower than {min_value}, fallback to {default}")
        return default
    return value


def env_bool(name: str, default: bool = False) -> bool:
    """
    业务功能：读取模板覆盖开关。
    关键流程：删除 index template 会影响后续新建索引，必须通过显式环境变量开启。
    """
    raw = os.getenv(name)
    if raw is None or str(raw).strip() == "":
        return default
    return str(raw).strip().lower() in {"1", "true", "yes", "y", "on"}


es_host = os.environ.get("ES_HOST", "http://localhost:9200")
es = Elasticsearch(hosts=[es_host])

template_name = os.getenv("KB_LEGACY_TEMPLATE_NAME", "kb_legacy_template")
index_pattern = os.getenv("KB_LEGACY_TEMPLATE_PATTERN", "kb_legacy_*")
template_priority = env_int("KB_LEGACY_TEMPLATE_PRIORITY", 10, min_value=0)
overwrite_template = env_bool("KB_LEGACY_TEMPLATE_OVERWRITE", False)

template_body = {
    "index_patterns": [index_pattern],
    "priority": template_priority,
    "template": {
        "settings": {
            "number_of_shards": env_int("KB_LEGACY_TEMPLATE_SHARDS", 1, min_value=1),
            "number_of_replicas": env_int("KB_LEGACY_TEMPLATE_REPLICAS", 0, min_value=0),
        },
        "mappings": {
            "properties": {
                "content": {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "fields": {
                        "keyword": {
                            "type": "keyword",
                            "ignore_above": 256,
                        }
                    },
                },
                "doc_title": {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "fields": {
                        "keyword": {
                            "type": "keyword",
                        }
                    },
                },
                "vector": {
                    "type": "dense_vector",
                    "dims": 1024,
                    "index": True,
                    "similarity": "cosine",
                    "index_options": {
                        "type": "hnsw",
                        "m": env_int("KB_LEGACY_TEMPLATE_HNSW_M", 16, min_value=1),
                        "ef_construction": env_int("KB_LEGACY_TEMPLATE_HNSW_EF_CONSTRUCTION", 100, min_value=1),
                    },
                },
                "source": {"type": "keyword"},
                "chunk_id": {"type": "integer"},
                "is_latest": {"type": "boolean"},
                "data_source": {"type": "keyword"},
                "owner_dept_id": {"type": "keyword"},
                "visible_depts": {"type": "keyword"},
            }
        },
    },
}

try:
    if overwrite_template and es.indices.exists_index_template(name=template_name):
        es.indices.delete_index_template(name=template_name)

    es.indices.put_index_template(
        name=template_name,
        index_patterns=[index_pattern],
        priority=template_priority,
        template=template_body["template"],
    )
    print(f"[Template] registered {template_name}, pattern={index_pattern}, priority={template_priority}")
except Exception as e:
    print(f"[Template] register failed: {e}")
