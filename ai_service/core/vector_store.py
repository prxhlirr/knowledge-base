import abc
import os
from dotenv import load_dotenv
from elasticsearch import Elasticsearch, helpers

load_dotenv()


def _env_int(name: str, default: int, min_value: int = 0) -> int:
    """
    业务功能：读取独立向量索引的整数型容量配置。
    关键流程：向量存储可能被本地脚本单独调用，不能依赖 ES 默认 settings；非法配置回退默认值。
    """
    raw = os.getenv(name)
    if raw is None or str(raw).strip() == "":
        return default
    try:
        value = int(str(raw).strip())
    except ValueError:
        print(f"[VectorStore] env {name}={raw!r} is not an integer, fallback to {default}")
        return default
    if value < min_value:
        print(f"[VectorStore] env {name}={value} is lower than {min_value}, fallback to {default}")
        return default
    return value


def vector_store_index_settings() -> dict:
    """
    业务功能：生成独立向量索引 settings。
    关键流程：通过环境变量控制分片和副本，避免本地工具在生产 ES 上静默创建固定 1/1 索引。
    """
    return {
        "number_of_shards": _env_int("KB_VECTOR_STORE_SHARDS", 1, min_value=1),
        "number_of_replicas": _env_int("KB_VECTOR_STORE_REPLICAS", 0, min_value=0),
    }


def vector_store_hnsw_options() -> dict:
    """
    业务功能：生成独立向量索引的 HNSW 参数。
    关键流程：亿级向量场景下 m/ef_construction 直接影响内存和构建成本，必须可按环境压测调优。
    """
    return {
        "type": "hnsw",
        "m": _env_int("KB_VECTOR_STORE_HNSW_M", 16, min_value=1),
        "ef_construction": _env_int("KB_VECTOR_STORE_HNSW_EF_CONSTRUCTION", 128, min_value=1),
    }


class BaseVectorStore(abc.ABC):
    """向量存储接口抽象层，为后期独立扩展 Milvus/Qdrant 预留标准化协议"""
    
    @abc.abstractmethod
    def setup_schema(self):
        """初始化索引结构与 Schema 定义"""
        pass
        
    @abc.abstractmethod
    def batch_insert(self, docs: list[dict], batch_size: int = 100):
        """批量写入带有特征向量与元数据的分片"""
        pass

class EsVectorStore(BaseVectorStore):
    """Elasticsearch 8.6.2 原生 kNN 向量检索实现"""
    
    def __init__(self, host: str = None, index_name: str = None):
        target_host = host or os.getenv("ES_HOST", "http://localhost:9200")
        target_index = index_name or os.getenv("ES_INDEX", "gov_doc_vector_v1")
        self.es = Elasticsearch(target_host)
        self.index_name = target_index

    def setup_schema(self):
        if self.es.indices.exists(index=self.index_name):
            print(f"Index {self.index_name} already exists. Skipping creation.")
            return
            
        mapping_body = {
            "settings": vector_store_index_settings(),
            "mappings": {
                "properties": {
                    # 1. 向量场
                    "vector": {
                        "type": "dense_vector",
                        "dims": 1024, # BGE-m3 输出1024维
                        "index": True,
                        "similarity": "cosine",
                        "index_options": vector_store_hnsw_options()
                    },
                    # 2. 标量字段与元数据
                    "doc_id": { "type": "keyword" },
                    "chunk_id": { "type": "keyword" },
                    "chunk_text": { "type": "text", "analyzer": "ik_max_word" },
                    "file_type": { "type": "keyword" },
                    "source_name": { "type": "keyword" },
                    # 生命周期与一致性标识
                    "doc_status": { "type": "keyword" },
                    "is_latest": { "type": "boolean" },
                    "doc_version": { "type": "integer" }
                }
            }
        }
        
        self.es.indices.create(index=self.index_name, body=mapping_body)
        print(f"✅ Successfully created Elasticsearch 8.6 HNSW index: {self.index_name}")

    def batch_insert(self, docs: list[dict], batch_size: int = 100):
        """批量写入 ES 的工具方法"""
        actions = []
        for doc in docs:
            # 兼容：如果提供了 _id 则使用，否则让 ES 自动分配，或按 hash 指定防重复覆盖
            action = {
                "_op_type": "index",
                "_index": self.index_name,
                "_source": doc
            }
            if "_id" in doc:
                 action["_id"] = doc.pop("_id")
                 
            actions.append(action)

        success, failed = helpers.bulk(self.es, actions, chunk_size=batch_size, raise_on_error=False)
        print(f"Batch insert completed. Success: {success}, Failed errors length: {len(failed)}")
        return success, failed

# 初始化全局唯一挂载对象 (依赖外部注入host配置，当前默认 localhost)
es_store = EsVectorStore()
