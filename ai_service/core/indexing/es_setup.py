import os
import time
import requests as _http
from elasticsearch import Elasticsearch

# docker中执行脚本如下：
# docker-compose run --rm -e ES_SETUP_MODE=init ai-service python scripts/es_init.py

# ── ES 索引名称常量（全系统统一入口，避免魔法字符串）──────────────────────────
# [架构重构] 从 rag_pipeline.py 提取，作为单一数据源，所有模块统一从此导入
INDEX_NAME     = os.getenv("KB_DOCUMENT_INDEX", "kb_document_v1")
QA_INDEX_NAME  = "kb_qa_pairs"       # QA 物理索引名（仅 init 创建时使用）
DOC_META_INDEX = os.getenv("KB_DOC_META_INDEX", "kb_doc_meta_v2")
DOC_META_READ_ALIAS = os.getenv("KB_DOC_META_READ_ALIAS", "kb_doc_meta_read")
DOC_META_WRITE_ALIAS = os.getenv("KB_DOC_META_WRITE_ALIAS", "kb_doc_meta_write")
DOC_SEARCH_INDEX = os.getenv("KB_DOC_SEARCH_INDEX", "kb_doc_search_v1")
DOC_SEARCH_READ_ALIAS = os.getenv("KB_DOC_SEARCH_READ_ALIAS", "kb_doc_search")
DOC_SEARCH_WRITE_ALIAS = os.getenv("KB_DOC_SEARCH_WRITE_ALIAS", "kb_doc_search_write")

# ── QA 索引读写别名（[轨道A] 别名化改造，业务代码统一使用别名不直接引用物理索引）─
# 设计：写别名 is_write_index=True 保证 bulk 精确路由；读别名无限制支持 Reindex 期间多索引并读
QA_INDEX_WRITE_ALIAS = "kb_qa_write"  # 所有写入（bulk index）走此别名
QA_INDEX_READ_ALIAS  = "kb_qa_read"   # 所有读查询（KNN、match）走此别名


def _env_int(name: str, default: int, min_value: int = 0) -> int:
    """
    业务功能：读取 ES 索引容量相关整数配置。
    关键流程：环境变量缺失或非法时回退默认值，避免错误配置生成不可用索引模板。
    """
    raw = os.getenv(name)
    if raw is None or str(raw).strip() == "":
        return default
    try:
        value = int(str(raw).strip())
    except ValueError:
        print(f"[ESSetup] env {name}={raw!r} is not an integer, fallback to {default}")
        return default
    if value < min_value:
        print(f"[ESSetup] env {name}={value} is lower than {min_value}, fallback to {default}")
        return default
    return value


def document_index_settings() -> dict:
    """
    业务功能：生成 kb_document_* 业务 chunk 索引 settings。
    关键流程：默认保持单节点兼容；生产扩容时通过环境变量创建新索引模板，不原地修改历史索引。
    """
    return {
        "number_of_shards": _env_int("KB_DOCUMENT_SHARDS", 1, min_value=1),
        "number_of_replicas": _env_int("KB_DOCUMENT_REPLICAS", 0, min_value=0),
    }


def doc_meta_index_settings() -> dict:
    """
    业务功能：生成 kb_doc_meta 文档级索引 settings。
    关键流程：独立于 chunk 索引配置，便于后续将文档级检索与 chunk 检索分开扩容。
    """
    return {
        "number_of_shards": _env_int("KB_DOC_META_SHARDS", 1, min_value=1),
        "number_of_replicas": _env_int("KB_DOC_META_REPLICAS", 0, min_value=0),
    }


def doc_search_index_settings() -> dict:
    """
    业务功能：生成 kb_doc_search 文档级预筛索引 settings。
    关键流程：保留 ngram analyzer 配置，同时把分片、副本和 ngram 差值配置集中管理。
    """
    return {
        "number_of_shards": _env_int("KB_DOC_SEARCH_SHARDS", 1, min_value=1),
        "number_of_replicas": _env_int("KB_DOC_SEARCH_REPLICAS", 0, min_value=0),
        "max_ngram_diff": _env_int("KB_DOC_SEARCH_MAX_NGRAM_DIFF", 6, min_value=1),
        "analysis": {
            "tokenizer": {
                "doc_ngram_tokenizer": {
                    "type": "ngram",
                    "min_gram": 2,
                    "max_gram": 8,
                }
            },
            "analyzer": {
                "ik_smart": {"type": "custom", "tokenizer": "ik_smart"},
                "ik_max_word": {"type": "custom", "tokenizer": "ik_max_word"},
                "doc_ngram": {
                    "type": "custom",
                    "tokenizer": "doc_ngram_tokenizer",
                    "filter": ["lowercase"],
                },
            },
        },
    }


def qa_index_settings() -> dict:
    """
    业务功能：生成 kb_qa_pairs 问答索引 settings。
    关键流程：QA 索引同样承载向量检索，必须和 chunk/doc 索引一样通过环境变量控制分片与副本，
              避免生产环境退回 ES 默认副本或单分片。
    """
    return {
        "number_of_shards": _env_int("KB_QA_SHARDS", 1, min_value=1),
        "number_of_replicas": _env_int("KB_QA_REPLICAS", 0, min_value=0),
    }


def _env_flag(name: str, default: bool = False) -> bool:
    """
    业务功能：读取布尔型开关配置。
    关键流程：生产保护需要显式开关绕过，统一解析能避免大小写和取值差异造成误判。
    """
    raw = os.getenv(name)
    if raw is None or str(raw).strip() == "":
        return default
    return str(raw).strip().lower() in {"1", "true", "yes", "y", "on"}


def _is_production_env() -> bool:
    """
    业务功能：判断当前是否处于生产部署上下文。
    关键流程：兼容 Spring、Python 和通用容器环境变量，只要任一 profile 标记为 prod/production 即启用保护。
    """
    names = ["APP_ENV", "ENV", "ENVIRONMENT", "PYTHON_ENV", "SPRING_PROFILES_ACTIVE"]
    values = ",".join(os.getenv(name, "") for name in names).lower()
    tokens = {item.strip() for part in values.split(",") for item in part.split(";")}
    return bool(tokens.intersection({"prod", "production"}))


def validate_production_index_settings() -> None:
    """
    业务功能：在生产初始化索引前校验分片和副本配置是否明显不适合生产。
    关键流程：默认开发配置允许单节点运行；生产环境必须显式配置多分片和副本，
              若确实是离线单节点演练，需要通过 ALLOW_SINGLE_NODE_ES=true 明确承担风险。
    """
    if not _is_production_env() or _env_flag("ALLOW_SINGLE_NODE_ES", False):
        return

    checks = [
        ("KB_DOCUMENT", document_index_settings(), 2, 1),
        ("KB_DOC_META", doc_meta_index_settings(), 1, 1),
        ("KB_DOC_SEARCH", doc_search_index_settings(), 1, 1),
        ("KB_QA", qa_index_settings(), 1, 1),
    ]
    violations = []
    for name, settings, min_shards, min_replicas in checks:
        if settings["number_of_shards"] < min_shards:
            violations.append(f"{name}_SHARDS={settings['number_of_shards']} < {min_shards}")
        if settings["number_of_replicas"] < min_replicas:
            violations.append(f"{name}_REPLICAS={settings['number_of_replicas']} < {min_replicas}")

    if violations:
        raise RuntimeError(
            "[ESSetup] 生产环境索引容量配置不安全，拒绝 init 创建索引："
            + "; ".join(violations)
            + "。如确认为离线单节点演练，请显式设置 ALLOW_SINGLE_NODE_ES=true。"
        )


def doc_meta_index_mapping() -> dict:
    return {
        "settings": doc_meta_index_settings(),
        "mappings": {
            "properties": {
                "doc_id": {"type": "keyword"},
                "doc_version": {"type": "integer"},
                "content_hash": {"type": "keyword"},
                "source": {"type": "keyword"},
                "source_name": {"type": "keyword"},
                "title": {
                    "type": "text",
                    "fields": {
                        "keyword": {"type": "keyword", "ignore_above": 256}
                    },
                },
                "summary": {"type": "text", "index": False},
                "doc_type": {"type": "keyword"},
                "data_source": {"type": "keyword"},
                "chunk_count": {"type": "integer"},
                "is_latest": {"type": "boolean"},
                "acl_tokens": {"type": "keyword"},
                "visibility": {"type": "keyword"},
                "owner_dept_id": {"type": "keyword"},
                "source_index": {"type": "keyword"},
                "index_code": {"type": "keyword"},
                "owner_unit_code": {"type": "keyword"},
                "visible_unit_codes": {"type": "keyword"},
                "permission_version": {"type": "long"},
                "updated_at": {"type": "date", "format": "epoch_millis"},
                "doc_vector": {
                    "type": "dense_vector",
                    "dims": 1024,
                    "index": True,
                    "similarity": "cosine",
                    "index_options": {"type": "hnsw", "m": 48, "ef_construction": 400},
                },
            }
        },
    }


def qa_index_mapping() -> dict:
    """
    业务功能：生成 Q&A 索引的完整 settings + mappings。
    关键流程：把 QA 向量字段、权限字段和容量 settings 放在同一个入口，
              后续迁移脚本和 init 创建流程才能共享同一份生产约束。
    """
    return {
        "settings": qa_index_settings(),
        "mappings": {
            "properties": {
                "question": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "question_vector": {
                    "type": "dense_vector",
                    "dims": 1024,
                    "index": True,
                    "similarity": "cosine",
                    "index_options": {"type": "hnsw", "m": 48, "ef_construction": 400},
                },
                "answer_content": {"type": "text"},
                "answer_chunk_id": {"type": "keyword"},
                "section_path": {"type": "keyword"},
                "source": {"type": "keyword"},
                "acl_tokens": {"type": "keyword"},
                "source_index": {"type": "keyword"},
                "index_code": {"type": "keyword"},
                "owner_unit_code": {"type": "keyword"},
                "visible_unit_codes": {"type": "keyword"},
                "permission_version": {"type": "long"},
                "doc_version": {"type": "integer"},
                "is_latest": {"type": "boolean"},
            }
        },
    }


def doc_search_index_mapping() -> dict:
    return {
        "settings": doc_search_index_settings(),
        "mappings": {
            "dynamic": "strict",
            "properties": {
                "doc_id": {"type": "keyword"},
                "doc_version": {"type": "integer"},
                "content_hash": {"type": "keyword"},
                "source": {
                    "type": "keyword",
                    "fields": {
                        "text": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                        "ngram": {"type": "text", "analyzer": "doc_ngram", "search_analyzer": "doc_ngram"},
                    },
                },
                "source_name": {"type": "keyword"},
                "title": {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "search_analyzer": "ik_smart",
                    "fields": {
                        "keyword": {"type": "keyword", "ignore_above": 256},
                        "ngram": {"type": "text", "analyzer": "doc_ngram", "search_analyzer": "doc_ngram"},
                    },
                },
                "document_number": {
                    "type": "keyword",
                    "fields": {
                        "text": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                        "ngram": {"type": "text", "analyzer": "doc_ngram", "search_analyzer": "doc_ngram"},
                    },
                },
                "keywords": {"type": "keyword"},
                "tags": {"type": "keyword"},
                "entities": {"type": "keyword"},
                "section_titles": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "doc_terms": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "summary": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "representative_chunk_ids": {"type": "keyword"},
                "doc_type": {"type": "keyword"},
                "data_source": {"type": "keyword"},
                "is_latest": {"type": "boolean"},
                "acl_tokens": {"type": "keyword"},
                "visibility": {"type": "keyword"},
                "owner_dept_id": {"type": "keyword"},
                "source_index": {"type": "keyword"},
                "index_code": {"type": "keyword"},
                "owner_unit_code": {"type": "keyword"},
                "visible_unit_codes": {"type": "keyword"},
                "permission_version": {"type": "long"},
                "publish_time": {"type": "date", "format": "yyyy-MM-dd||epoch_millis"},
                "chunk_count": {"type": "integer"},
                "updated_at": {"type": "date", "format": "epoch_millis"},
            },
        },
    }


def _alias_targets(es: Elasticsearch, alias: str) -> list:
    """
    业务功能：返回某别名当前指向的物理索引列表。
    关键流程：别名不存在或查询失败时返回空列表；用于 safe 模式索引校验和 init 模式幂等判断，
              让校验在“旧物理索引已被清理、流量已切到 v2”的迁移后状态依然成立，
              也避免 init 在清理后重建旧索引造成迁移静默回滚。
    """
    try:
        refs = es.indices.get_alias(name=alias)
    except Exception:
        return []
    return list((refs or {}).keys())


class ESSetup:
    """
    业务功能：管理 Elasticsearch 索引的生命周期初始化。
    从 RAGPipeline 提取（架构重构阶段一），职责隔离：只负责 ES 基础设施，不参与业务逻辑。

    关键流程：
      safe   模式（默认，服务启动时）: _verify_only() 动态拉取路由表，只读校验所有活跃索引是否存在
      init   模式（首次部署 / CI Init Job）: 创建索引 + 注册 Template，只创建不改动已有
      migrate 模式（Mapping 字段追加，人工触发）: 仅执行 _update_mapping()

    设计原则：启动时只读，变更时显式；绝不在服务启动路径上执行破坏性操作。
    """

    def __init__(self, es: Elasticsearch):
        self.es = es

    def setup(self, mode: str = None):
        """
        业务功能：ES 基础设施入口，根据运行模式决定执行行为。
        """
        mode = mode or os.getenv("ES_SETUP_MODE", "safe")
        print(f"[ESSetup] 运行模式: {mode}")

        if mode == "safe":
            self._verify_only()
        elif mode == "init":
            validate_production_index_settings()
            # [Fix] 必须先注册 Template，确保后续动态创建的路由分区索引能继承结构
            self._ensure_template()
            self._ensure_index()
            self._ensure_qa_index()
            self._ensure_partition_write_aliases()  # [轨道B] 为所有活跃分区注册写别名
        elif mode == "migrate":
            self._update_mapping()
        else:
            raise ValueError(f"[ESSetup] 未知 ES_SETUP_MODE: '{mode}'，有效值: safe / init / migrate")

    def _verify_only(self):
        """
        业务功能：服务启动时的只读安全自检（safe 模式专用）。

        关键流程：
          1. 调用 Java 服务 /api/v1/internal/index/routing 拉取所有活跃路由规则
             （sys_index_routing 表中 is_active=1 的全量 target_index 集合）
          2. 固定追加 kb_qa_pairs（QA 索引，不在路由表中）
          3. 对上述所有索引逐一验证 ES 中是否存在，任一缺失则抛出异常，拒绝服务启动
          4. 额外校验 kb_qa_pairs.question_vector 字段类型，类型错误时同样拒绝启动

        设计决策：
          - 动态拉取路由表而非硬编码：新增文档类型注册后无需修改此方法
          - 拉取失败时降级使用 kb_document_v1 作为最小必要集（兜底保证基础功能可用）
          - 严格 Fail-Fast：宁可拒绝启动暴露问题，不自动创建/修复掩盖问题
        """
        # 1. 动态拉取路由表中所有活跃索引
        active_indices = set()
        java_host = os.getenv("JAVA_SERVICE_HOST", "http://localhost:8080")
        token     = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
        try:
            resp = _http.get(
                f"{java_host}/api/v1/internal/routing/active",
                headers={"X-Internal-Token": token},
                timeout=5
            )
            if resp.status_code == 200:
                routing_data = resp.json().get("data", {})
                # routing_data: { tagName -> targetIndex }，取全部 targetIndex 值做去重
                active_indices = set(routing_data.values())
                print(f"[ESSetup] 从路由表拉取到 {len(active_indices)} 个活跃索引: {active_indices}")
            else:
                print(f"⚠️ [ESSetup] 路由表接口返回 HTTP {resp.status_code}，降级使用最小必要集")
        except Exception as e:
            # 网络不通（如 Java 尚未启动）时降级，不阻断 safe 模式校验路径
            print(f"⚠️ [ESSetup] 拉取路由表失败（{e}），降级使用最小必要集: {INDEX_NAME}")

        # 降级兜底：至少保证默认索引在校验范围内
        if not active_indices:
            active_indices = {INDEX_NAME}

        # 2. QA 索引不在路由表中，单独按读别名校验（见第 4 步），此处不再硬编码物理名，
        #    以兼容“已迁移到 kb_qa_pairs_v2 且旧 kb_qa_pairs 已清理”的状态。

        # 3. 逐一校验业务索引是否存在
        #    容错：物理索引可能已在迁移后被清理，只要其 {idx}_write 别名仍指向 v2，视为存在。
        missing = []
        for idx in active_indices:
            if self.es.indices.exists(index=idx):
                continue
            if _alias_targets(self.es, f"{idx}_write"):
                continue
            missing.append(idx)
        if missing:
            raise RuntimeError(
                f"[ESSetup] ❌ 以下索引在 ES 中不存在，服务拒绝启动！\n"
                f"  缺失索引: {missing}\n"
                f"  解决方案: 以 ES_SETUP_MODE=init 运行 scripts/es_init.py 创建缺失索引"
            )

        # 4. 校验 QA 索引（按读别名解析物理索引，兼容已迁移到 kb_qa_pairs_v2 的状态）
        qa_physicals = _alias_targets(self.es, QA_INDEX_READ_ALIAS)
        if not qa_physicals and self.es.indices.exists(index=QA_INDEX_NAME):
            qa_physicals = [QA_INDEX_NAME]  # 未迁移的旧环境兜底
        if not qa_physicals:
            raise RuntimeError(
                f"[ESSetup] ❌ QA 索引不可用：读别名 {QA_INDEX_READ_ALIAS} 未解析到任何物理索引，"
                f"且旧索引 {QA_INDEX_NAME} 也不存在，服务拒绝启动！\n"
                f"  解决方案: 以 ES_SETUP_MODE=init 运行 scripts/es_init.py 创建 QA 索引并注册读写别名"
            )
        qa_check_index = qa_physicals[0]
        try:
            mapping = self.es.indices.get_mapping(index=qa_check_index)
            qv_type = (
                mapping.get(qa_check_index, {})
                .get("mappings", {}).get("properties", {})
                .get("question_vector", {}).get("type", "")
            )
            if qv_type and qv_type != "dense_vector":
                raise RuntimeError(
                    f"[ESSetup] ❌ {qa_check_index}.question_vector 类型为 '{qv_type}'（期望 dense_vector）。\n"
                    f"  此索引需要 Reindex 重建，禁止自动删除！\n"
                    f"  Reindex 步骤:\n"
                    f"    1. 以新名创建正确 Mapping 的索引（如 kb_qa_pairs_v2）\n"
                    f"    2. 执行 POST _reindex：src=kb_qa_pairs, dest=kb_qa_pairs_v2\n"
                    f"    3. 原子切换 {QA_INDEX_READ_ALIAS}/{QA_INDEX_WRITE_ALIAS} 指向 kb_qa_pairs_v2\n"
                    f"    4. 确认无误后再清理旧索引 kb_qa_pairs"
                )
        except RuntimeError:
            raise
        except Exception as e:
            print(f"⚠️ [ESSetup] 获取 QA Mapping 校验失败（跳过）: {e}")

        print(f"✅ [ESSetup] safe 模式校验通过，共检查 {len(active_indices)} 个索引")

    def _ensure_index(self):
        """
        业务功能：确保主文档索引和元数据索引存在，不存在则按标准 Mapping 创建。
        关键流程：先检查 kb_document_v1，再检查 kb_doc_meta，均为幂等操作。
        """
        if not self.es.indices.exists(index=INDEX_NAME):
            print(f"⚠️ 索引 {INDEX_NAME} 不存在，正在自动创建...")
            mapping = {
                "settings": document_index_settings(),
                "mappings": {
                    "properties": {
                        "content": {
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart"
                        },
                        "vector": {
                            "type": "dense_vector",
                            "dims": 1024,
                            "index": True,
                            "similarity": "cosine",
                            "index_options": {"type": "hnsw", "m": 48, "ef_construction": 400},
                        },
                        "acl_tokens": {"type": "keyword"},
                        "keywords": {"type": "keyword"},
                        "source_index": {"type": "keyword"},
                        "index_code": {"type": "keyword"},
                        "owner_unit_code": {"type": "keyword"},
                        "visible_unit_codes": {"type": "keyword"},
                        "permission_version": {"type": "long"},
                        "metadata": {
                            "properties": {
                                "source": {"type": "keyword"},
                                "chunk_id": {"type": "integer"},
                                "is_latest": {"type": "boolean"},
                                "data_source": {"type": "keyword"},
                                "owner_dept_id": {"type": "keyword"},
                                "visible_depts": {"type": "keyword"},
                                "acl_tokens": {"type": "keyword"},
                                "source_index": {"type": "keyword"},
                                "index_code": {"type": "keyword"},
                                "owner_unit_code": {"type": "keyword"},
                                "visible_unit_codes": {"type": "keyword"},
                                "permission_version": {"type": "long"},
                                "tags": {"type": "keyword"},
                                "document_number": {"type": "keyword"},
                                # [Fix] dynamic:false 防止 dynamic_meta 写入随机 key 导致字段数量爆炸
                                # ES 默认字段上限 1000，dynamic:true 时业务方写入新 key 会自动扩展 Mapping
                                "dynamic_meta": {
                                    "type": "object",
                                    "dynamic": False
                                },
                                "owner": {"type": "keyword"},
                                "search_queries": {"type": "text"}
                            }
                        }
                    }
                }
            }
            self.es.indices.create(index=INDEX_NAME, body=mapping)
            print(f"✅ 索引 {INDEX_NAME} 创建成功！")

        # 确保 kb_doc_meta 元数据索引也存在
        if not self.es.indices.exists(index=DOC_META_INDEX):
            self.es.indices.create(index=DOC_META_INDEX, body=doc_meta_index_mapping())
            print(f"[DocMeta] index {DOC_META_INDEX} created with dense doc_vector mapping")
        self._ensure_doc_meta_aliases()
        self._ensure_doc_search_index()

        # [Fix] 动态拉取路由表，为 Java 业务端新增的分类分区创建 ES 索引
        java_host = os.getenv("JAVA_SERVICE_HOST", "http://localhost:8080")
        token     = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
        try:
            resp = _http.get(
                f"{java_host}/api/v1/internal/routing/active",
                headers={"X-Internal-Token": token},
                timeout=5
            )
            if resp.status_code == 200:
                routing_data = resp.json().get("data", {})
                for tag_name, idx_name in routing_data.items():
                    if not self.es.indices.exists(index=idx_name):
                        print(f"⚠️ 检测到新建分类路由 ({tag_name} -> {idx_name})，正在自动创建...")
                        # 此时 _ensure_template 已经执行，直接创建空索引即可完美继承 mapping 配置！
                        self.es.indices.create(index=idx_name)
                        print(f"✅ 动态路由索引 {idx_name} 创建成功！")
        except Exception as e:
            print(f"ℹ️ [ESSetup] 未能拉取到动态路由表或网络异常，跳过动态索引创建。({e})")

    def _update_mapping(self):
        """
        业务功能：为已有索引热更新 mapping，追加 Phase 1 新增字段定义（幂等，字段已存在则忽略）。
        新增：版本控制字段（doc_version/version_at/updated_by）、
              部门编码分级字段（dept_l2/l4/l6/l9/dept_code_full）、
              权限字段（visibility/access_groups）、标签（tags keyword 版）。
        """
        try:
            self.es.indices.put_mapping(
                index=INDEX_NAME,
                body={
                    "properties": {
                        "display_content":   {"type": "text"},  # [P0-5A] 含面包屑的展示内容，为防冲突移除 index: False
                        "chunk_granularity": {"type": "keyword"},
                        "parent_chunk_id":   {"type": "keyword"},
                        "sparse_vector":     {"type": "rank_features"},
                        "acl_tokens":        {"type": "keyword"},
                        "source_index":      {"type": "keyword"},
                        "index_code":        {"type": "keyword"},
                        "owner_unit_code":   {"type": "keyword"},
                        "visible_unit_codes": {"type": "keyword"},
                        "permission_version": {"type": "long"},
                        # [Fix] colloquial_vector 已从 _update_mapping 移除！
                        # ES 8.x 硬性规则：dense_vector 一旦建立 HNSW 索引（index:true），
                        # 不允许通过 put_mapping 热修改为 index:false，否则报 mapping conflict 错误。
                        # 新建索引时 _ensure_index 不再创建此字段（保持索引纯净）；
                        # 历史索引无此字段时，写入Pipeline自动跳过（Python ES client 不允许写 mapping 外字段）。
                        # 若未来需要此字段，走 Reindex 流程（不可避免的重建索引）。
                        "metadata": {
                            "properties": {
                                "document_number": {"type": "keyword"},
                                # [缺陷6 修复] section_path 改为 text+keyword 双字段
                                # 原因：纯 keyword 只支持精确 term query，无法按「第三条」前缀查询子条款
                                # text：支持 ik_smart 分词搜索（用户自然语言搜章节名）
                                # .keyword：支持精确 term + prefix query（Java 临近手写 DSL 用）
                                # 已有索引做打 put_mapping 追加 fields 子字段不需要 reindex
                                "section_path": {
                                    "type": "text",
                                    "analyzer": "ik_smart",
                                    "fields": {
                                        "keyword": {"type": "keyword"}
                                    }
                                },
                                "chunk_type":      {"type": "keyword"},
                                "quality_score":   {"type": "float"},
                                # [Fix] dynamic_meta 统一为 dynamic:false（见下方显式声明，此行原为 dynamic:True 的残留重复，已移除）
                                # [标题检索修复] 独立 title 字段：text 类型支持 IK 分词检索，
                                # .keyword 子字段保留精确匹配能力（词语完全相同时可 term query）
                                "title": {
                                    "type": "text",
                                    "analyzer": "ik_max_word",
                                    "search_analyzer": "ik_smart",
                                    "fields": {
                                        "keyword": {"type": "keyword"}
                                    }
                                },
                                "owner":           {"type": "keyword"},
                                "search_queries":  {"type": "text"},
                                # --- 版本管理字段 (B1/B2) ---
                                "doc_version":  {"type": "integer"},
                                "version_at":   {"type": "date", "format": "epoch_millis"},
                                "updated_by":   {"type": "keyword"},
                                # --- 部门编码分级字段 (C1) ---
                                "dept_l2":        {"type": "keyword"},
                                "dept_l4":        {"type": "keyword"},
                                "dept_l6":        {"type": "keyword"},
                                "dept_l9":        {"type": "keyword"},
                                "dept_code_full": {"type": "keyword"},
                                # --- 权限与标签字段 (C2/C6) ---
                                "visibility":      {"type": "keyword"},
                                "acl_tokens":      {"type": "keyword"},
                                "access_groups":   {"type": "keyword"},
                                "source_index":    {"type": "keyword"},
                                "index_code":      {"type": "keyword"},
                                "owner_unit_code": {"type": "keyword"},
                                "visible_unit_codes": {"type": "keyword"},
                                "permission_version": {"type": "long"},
                                "uploader_id":     {"type": "keyword"},
                                "handler_user_ids":   {"type": "keyword"},
                                "handler_dept_l6":    {"type": "keyword"},
                                "tags_kw":         {"type": "keyword"},
                                # [Fix] dynamic_meta 改为 dynamic:false，防止任意 key 写入导致字段数爆炸
                                # 业务需要新增 key 时，在 _update_mapping 中显式声明，受控扩展
                                "dynamic_meta":    {"type": "object", "dynamic": False},
                            }
                        }
                    }
                }
            )
            print(f"✅ [Mapping] Phase 1 新增字段（版本/部门/权限）已热更新到索引 {INDEX_NAME}")
        except Exception as e:
            print(f"⚠️ [Mapping] 更新 mapping 失败（可能索引尚未创建或冲突）: {e}")

    def _ensure_doc_meta_aliases(self):
        actions = []
        try:
            current = self.es.indices.get_alias(index=DOC_META_INDEX).get(DOC_META_INDEX, {}).get("aliases", {})
        except Exception:
            current = {}

        for alias in (DOC_META_READ_ALIAS, DOC_META_WRITE_ALIAS):
            try:
                alias_refs = self.es.indices.get_alias(name=alias)
            except Exception:
                alias_refs = {}
            for index_name in alias_refs.keys():
                if index_name != DOC_META_INDEX:
                    actions.append({"remove": {
                        "index": index_name,
                        "alias": alias,
                    }})

        if DOC_META_READ_ALIAS not in current:
            actions.append({"add": {
                "index": DOC_META_INDEX,
                "alias": DOC_META_READ_ALIAS,
            }})
        if current.get(DOC_META_WRITE_ALIAS, {}).get("is_write_index") is not True:
            if DOC_META_WRITE_ALIAS in current:
                actions.append({"remove": {
                    "index": DOC_META_INDEX,
                    "alias": DOC_META_WRITE_ALIAS,
                }})
            actions.append({"add": {
                "index": DOC_META_INDEX,
                "alias": DOC_META_WRITE_ALIAS,
                "is_write_index": True,
            }})

        if actions:
            self.es.indices.update_aliases(body={"actions": actions})
            print(f"[DocMeta] aliases registered for {DOC_META_INDEX}: {DOC_META_READ_ALIAS}, {DOC_META_WRITE_ALIAS}")

    def _ensure_doc_search_index(self):
        if not self.es.indices.exists(index=DOC_SEARCH_INDEX):
            self.es.indices.create(index=DOC_SEARCH_INDEX, body=doc_search_index_mapping())
            print(f"[DocSearch] index {DOC_SEARCH_INDEX} created for document-level keyword retrieval")
        self._ensure_doc_search_aliases()

    def _ensure_doc_search_aliases(self):
        actions = []
        try:
            current = self.es.indices.get_alias(index=DOC_SEARCH_INDEX).get(DOC_SEARCH_INDEX, {}).get("aliases", {})
        except Exception:
            current = {}

        for alias in (DOC_SEARCH_READ_ALIAS, DOC_SEARCH_WRITE_ALIAS):
            try:
                alias_refs = self.es.indices.get_alias(name=alias)
            except Exception:
                alias_refs = {}
            for index_name in alias_refs.keys():
                if index_name != DOC_SEARCH_INDEX:
                    actions.append({"remove": {
                        "index": index_name,
                        "alias": alias,
                    }})

        if DOC_SEARCH_READ_ALIAS not in current:
            actions.append({"add": {
                "index": DOC_SEARCH_INDEX,
                "alias": DOC_SEARCH_READ_ALIAS,
            }})
        if current.get(DOC_SEARCH_WRITE_ALIAS, {}).get("is_write_index") is not True:
            if DOC_SEARCH_WRITE_ALIAS in current:
                actions.append({"remove": {
                    "index": DOC_SEARCH_INDEX,
                    "alias": DOC_SEARCH_WRITE_ALIAS,
                }})
            actions.append({"add": {
                "index": DOC_SEARCH_INDEX,
                "alias": DOC_SEARCH_WRITE_ALIAS,
                "is_write_index": True,
            }})

        if actions:
            self.es.indices.update_aliases(body={"actions": actions})
            print(f"[DocSearch] aliases registered for {DOC_SEARCH_INDEX}: {DOC_SEARCH_READ_ALIAS}, {DOC_SEARCH_WRITE_ALIAS}")

    def _ensure_qa_index(self):
        """
        业务功能：确保 Q&A 索引 kb_qa_pairs 存在（init 模式专用）。
        Mapping 设计：question(文本+向量) + answer_content(条文原文) + 源字段

        [安全改造] 彻底移除自动 delete 逻辑：
          根因：原代码在检测到 question_vector 字段类型错误时直接执行 es.indices.delete()，
                无备份、无确认、无回滚，是生产环境的定时炸弹。
          新行为：init 模式下只允许创建索引（exists=True 时直接返回）。
                  字段类型校验和 delete 权限由 _verify_only()（safe 模式）和人工 Reindex 流程负责，
                  任何时候都禁止在服务启动路径上自动删除生产索引。
        """
        _target_mapping = {
            "properties": {
                "question":        {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "question_vector": {"type": "dense_vector", "dims": 1024, "index": True, "similarity": "cosine",
                                    "index_options": {"type": "hnsw", "m": 48, "ef_construction": 400}},
                "answer_content":  {"type": "text"},
                "answer_chunk_id": {"type": "keyword"},
                "section_path":    {"type": "keyword"},
                "source":          {"type": "keyword"},
                "acl_tokens":      {"type": "keyword"},
                "source_index":    {"type": "keyword"},
                "index_code":      {"type": "keyword"},
                "owner_unit_code": {"type": "keyword"},
                "visible_unit_codes": {"type": "keyword"},
                "permission_version": {"type": "long"},
                "doc_version":     {"type": "integer"},  # [版本化] 供 registerDoc 2PC 精确版本匹配和 is_latest 切换
                "is_latest":       {"type": "boolean"}
            }
        }

        # 别名优先：若 kb_qa_read 已解析到某物理索引（如已迁移到 kb_qa_pairs_v2），
        # 视为 QA 已就绪——仅幂等补注别名，绝不重建旧物理索引 QA_INDEX_NAME，
        # 否则“迁移后清理旧索引 → 再跑 init”会重建旧索引并把别名挂回，静默回滚整个迁移。
        existing_via_alias = _alias_targets(self.es, QA_INDEX_READ_ALIAS)
        if existing_via_alias:
            print(f"ℹ️ [ESSetup] {QA_INDEX_READ_ALIAS} 已指向 {existing_via_alias}，QA 索引就绪，跳过创建")
            self._ensure_qa_aliases()
            return

        if self.es.indices.exists(index=QA_INDEX_NAME):
            # init 模式：索引已存在则直接返回，严禁任何删除或修改操作
            print(f"ℹ️ [ESSetup] {QA_INDEX_NAME} 已存在，跳过创建")
            # 幂等补注alias（兼容已有环境首次升级的场景）
            self._ensure_qa_aliases()
            return

        print(f"[ESSetup] 创建 Q&A 索引 {QA_INDEX_NAME}...")
        self.es.indices.create(index=QA_INDEX_NAME, body=qa_index_mapping())
        print(f"✅ [ESSetup] {QA_INDEX_NAME} 创建成功（question_vector=dense_vector/1024/cosine）")
        # 新建索引后立即注册读写别名
        self._ensure_qa_aliases()

    def _ensure_qa_aliases(self):
        """
        业务功能：为 kb_qa_pairs 物理索引注册读写别名（幂等）。

        关键流程：
          1. 检查 kb_qa_write 别名是否已挂载 → 未挂载则注册（is_write_index=True）
          2. 检查 kb_qa_read 别名是否已挂载  → 未挂载则注册
          3. 在一个 update_aliases 原子请求中完成，保证一致性

        设计原则：
          - 写别名设 is_write_index=True：ES 多索引场景下 bulk 写入必须有且只有一个 write_index
          - 读别名无限制：Reindex 期间可同时指向新旧两个物理索引，结果自然合并
          - 幂等：别名已存在时静默跳过，可安全多次调用（init / 服务重启均安全）
        """
        # 别名感知：分别按 kb_qa_read / kb_qa_write 解析当前正名物理索引。
        # 已迁移到 kb_qa_pairs_v2 时，正名为 v2；未迁移或新建时回退 QA_INDEX_NAME。
        # 仅当别名未挂在其正名物理上时才补注，避免在已迁移环境把别名挂回旧索引
        # （触发 is_write_index 冲突或部分回滚迁移）。
        read_targets = _alias_targets(self.es, QA_INDEX_READ_ALIAS)
        read_canon = read_targets[0] if read_targets else QA_INDEX_NAME
        write_targets = _alias_targets(self.es, QA_INDEX_WRITE_ALIAS)
        write_canon = write_targets[0] if write_targets else QA_INDEX_NAME

        actions = []
        try:
            read_current = self.es.indices.get_alias(index=read_canon).get(read_canon, {}).get("aliases", {})
        except Exception:
            read_current = {}
        try:
            write_current = self.es.indices.get_alias(index=write_canon).get(write_canon, {}).get("aliases", {})
        except Exception:
            write_current = {}

        if QA_INDEX_WRITE_ALIAS not in write_current:
            actions.append({"add": {
                "index": write_canon,
                "alias": QA_INDEX_WRITE_ALIAS,
                "is_write_index": True
            }})
        if QA_INDEX_READ_ALIAS not in read_current:
            actions.append({"add": {
                "index": read_canon,
                "alias": QA_INDEX_READ_ALIAS
            }})

        if not actions:
            print(f"ℹ️ [ESSetup] QA 别名已就绪（read→{read_canon}, write→{write_canon}），跳过注册")
            return

        self.es.indices.update_aliases(body={"actions": actions})
        print(f"✅ [ESSetup] QA 别名注册完成: read→{read_canon}, write→{write_canon}")


    def _ensure_template(self):
        """
        业务功能：向 ES 注册 Index Template，确保 kb_document_* 系列索引统一继承标准 mapping。
        关键流程：
          1. 注册/更新名为 kb_document_template 的 Index Template（幂等）
          2. Template 覆盖 kb_document_* 通配符，新建索引时自动套用 mapping 和 alias
          3. 同时为 kb_document_v1 添加 kb_document 别名（如未添加），方便 Java 侧无缝切换索引版本
        此方法在服务启动时调用一次，后续无需重复注册。
        """
        try:
            # 注册模板（put_index_template 是幂等的，已存在时覆盖更新）
            self.es.indices.put_index_template(
                name="kb_document_template",
                body={
                    "index_patterns": ["kb_document_*"],
                    "priority": 100,
                    "template": {
                        "aliases": {
                            "kb_document": {}  # 别名，Java 侧通过此别名查询无需关心具体版本号
                        },
                        "settings": {
                            **document_index_settings(),
                            "analysis": {
                                "analyzer": {
                                    "ik_smart":    {"type": "custom", "tokenizer": "ik_smart"},
                                    "ik_max_word": {"type": "custom", "tokenizer": "ik_max_word"}
                                }
                            }
                        },
                        "mappings": {
                            "properties": {
                                "content":  {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                                "vector":   {"type": "dense_vector", "dims": 1024, "index": True, "similarity": "cosine"},
                                "sparse_vector":     {"type": "rank_features"},
                                # [Fix] colloquial_vector 改为 index:false，无检索逻辑使用，避免冗余 HNSW 索引
                        "colloquial_vector": {"type": "dense_vector", "dims": 1024, "index": False},
                                "chunk_granularity": {"type": "keyword"},
                                "parent_chunk_id":   {"type": "keyword"},
                                "keywords":          {"type": "keyword"},
                                "acl_tokens":        {"type": "keyword"},
                                "source_index":      {"type": "keyword"},
                                "index_code":        {"type": "keyword"},
                                "owner_unit_code":   {"type": "keyword"},
                                "visible_unit_codes": {"type": "keyword"},
                                "permission_version": {"type": "long"},
                                "metadata": {
                                    "properties": {
                                        "source":       {"type": "keyword"},
                                        "chunk_id":     {"type": "integer"},
                                        "is_latest":    {"type": "boolean"},
                                        "doc_version":  {"type": "integer"},
                                        "version_at":   {"type": "date", "format": "epoch_millis"},
                                        "updated_by":   {"type": "keyword"},
                                        "dept_l2":      {"type": "keyword"},
                                        "dept_l4":      {"type": "keyword"},
                                        "dept_l6":      {"type": "keyword"},
                                        "dept_l9":      {"type": "keyword"},
                                        "dept_code_full": {"type": "keyword"},
                                        "visibility":   {"type": "keyword"},
                                        "acl_tokens":   {"type": "keyword"},
                                        "access_groups":{"type": "keyword"},
                                        "uploader_id":  {"type": "keyword"},
                                        "tags_kw":      {"type": "keyword"},
                                        "tags":         {"type": "text"},
                                        "quality_score":{"type": "float"},
                                        "data_source":  {"type": "keyword"},
                                        "owner_dept_id":{"type": "keyword"},
                                        "source_index": {"type": "keyword"},
                                        "index_code": {"type": "keyword"},
                                        "owner_unit_code": {"type": "keyword"},
                                        "visible_unit_codes": {"type": "keyword"},
                                        "permission_version": {"type": "long"},
                                        "publish_time": {"type": "date", "format": "yyyy-MM-dd||epoch_millis"},
                                        "document_number": {"type": "keyword"},
                                        # [缺陷6 修复] section_path 双字段（服务模板，新建索引自动继承）
                                        "section_path": {
                                            "type": "text",
                                            "analyzer": "ik_smart",
                                            "fields": {
                                                "keyword": {"type": "keyword"}
                                            }
                                        },
                                        # [标题检索修复] title 字段：text 类型 + .keyword 子字段
                                        "title": {
                                            "type": "text",
                                            "analyzer": "ik_max_word",
                                            "search_analyzer": "ik_smart",
                                            "fields": {
                                                "keyword": {"type": "keyword"}
                                            }
                                        },
                                        # [Fix] dynamic_meta 改为 dynamic:false（Template 层兜底）
                                        "dynamic_meta": {"type": "object", "dynamic": False},
                                        "owner":        {"type": "keyword"},
                                        "search_queries": {"type": "text"}
                                    }
                                }
                            }
                        }
                    }
                }
            )
            print(f"✅ [D1] Index Template kb_document_template 已注册（覆盖 kb_document_*）")

            # 确保当前 kb_document_v1 挂载了 kb_document 别名
            aliases = self.es.indices.get_alias(index=INDEX_NAME, ignore_unavailable=True)
            if aliases and INDEX_NAME in aliases:
                existing_aliases = aliases[INDEX_NAME].get("aliases", {})
                if "kb_document" not in existing_aliases:
                    self.es.indices.put_alias(index=INDEX_NAME, name="kb_document")
                    print(f"✅ [D1] 别名 kb_document → {INDEX_NAME} 已绑定")
                else:
                    print(f"ℹ️ [D1] 别名 kb_document → {INDEX_NAME} 已存在，跳过")
        except Exception as e:
            print(f"⚠️ [D1] Index Template 注册失败（不阻塞主流程）: {e}")

    def _ensure_partition_write_aliases(self):
        """
        业务功能：为所有 kb_document_* 物理分区索引注册写别名，激活 rag_pipeline.py 中
                  已有的「写别名优先 fallback 到物理索引」逻辑。

        关键流程：
          1. 扫描 ES 中所有 kb_document_* 物理索引（cat/indices）
          2. 对每个物理索引检查 {index}_write 别名是否存在
          3. 不存在则注册（is_write_index=True），存在则跳过（幂等）
          4. 同样为 kb_doc_meta 注册 kb_doc_meta_write 别名

        命名规则（与 QA 完全对称）：
          kb_document_law → kb_document_law_write (is_write_index=True)
          kb_qa_pairs     → kb_qa_write           (is_write_index=True) ← 已有

        设计原则：
          - 写别名 is_write_index=True：保证向别名 bulk 写入时精确路由到单一物理索引
          - 读别名（kb_document）不设 is_write_index：多索引并读，Reindex 期间透明合并
          - 幂等：多次调用安全，已注册别名静默跳过
        """
        print("[ESSetup] 扫描并注册分区写别名...")
        registered = []
        skipped = []

        try:
            # 1. 获取所有 kb_document_* 物理索引（排除 kb_doc_meta）
            cat_resp = self.es.cat.indices(index="kb_document_*", h="index", format="json")
            partition_indices = [r["index"] for r in cat_resp if r["index"].startswith("kb_document_")]
        except Exception as e:
            print(f"⚠️ [ESSetup] 获取分区索引列表失败: {e}")
            partition_indices = []

        # 2. kb_doc_meta 追加写别名（独立于 kb_document_* 体系）
        partition_indices_with_meta = partition_indices

        for phys_index in partition_indices_with_meta:
            # 写别名命名规则：{物理索引名}_write
            write_alias = f"{phys_index}_write"
            try:
                # 迁移感知：若该写别名已指向另一个物理索引（如已迁到 {phys}_v2），
                # 说明此索引已迁移、被降级为旧索引，绝不再把 write_index 挂回来——
                # 否则会与 v2 上的 is_write_index=true 冲突（"more than one write index"）。
                alias_owner = _alias_targets(self.es, write_alias)
                migrated_owners = [owner for owner in alias_owner if owner != phys_index]
                if migrated_owners:
                    print(f"  ℹ️ [ESSetup] {write_alias} 已指向 {migrated_owners}（{phys_index} 已迁移/降级），跳过注册")
                    skipped.append(f"{write_alias} (migrated→{migrated_owners[0]})")
                    continue

                existing = self.es.indices.get_alias(index=phys_index).get(phys_index, {}).get("aliases", {})
                if write_alias in existing:
                    skipped.append(write_alias)
                    continue
                # 注册写别名（is_write_index=True，保证 bulk 精确路由）
                self.es.indices.update_aliases(body={"actions": [{
                    "add": {
                        "index":          phys_index,
                        "alias":          write_alias,
                        "is_write_index": True
                    }
                }]})
                registered.append(f"{write_alias} → {phys_index}")
            except Exception as e:
                print(f"  ⚠️ [ESSetup] 注册写别名 {write_alias} 失败: {e}")

        if registered:
            print(f"✅ [ESSetup] 写别名注册完成: {registered}")
        if skipped:
            print(f"ℹ️ [ESSetup] 以下写别名已存在，跳过: {skipped}")
        if not registered and not skipped:
            print("ℹ️ [ESSetup] 未发现任何 kb_document_* 索引，跳过写别名注册")
