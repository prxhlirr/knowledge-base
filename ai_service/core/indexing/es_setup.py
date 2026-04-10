from elasticsearch import Elasticsearch

# ── ES 索引名称常量（全系统统一入口，避免魔法字符串）──────────────────────────
# [架构重构] 从 rag_pipeline.py 提取，作为单一数据源，所有模块统一从此导入
INDEX_NAME     = "kb_document_v1"
QA_INDEX_NAME  = "kb_qa_pairs"
DOC_META_INDEX = "kb_doc_meta"


class ESSetup:
    """
    业务功能：管理 Elasticsearch 索引的生命周期初始化。
    从 RAGPipeline 提取（架构重构阶段一），职责隔离：只负责 ES 基础设施，不参与业务逻辑。

    关键流程：
      1. _ensure_index()   - 创建主文档索引 + 元数据索引（含 Mapping）
      2. _update_mapping() - 热更新 Mapping，追加版本/部门/权限等字段（幂等）
      3. _ensure_qa_index() - 创建 QA 对索引
      4. _ensure_template() - 注册 Index Template，kb_document_* 系列统一规范

    所有方法均幂等：索引已存在时静默跳过，不影响生产运行。
    """

    def __init__(self, es: Elasticsearch):
        self.es = es

    def setup(self):
        """
        业务功能：一键执行全量索引初始化，在 RAGPipeline.__init__ 中调用一次。
        关键流程：按序执行四步初始化，任一步骤失败不会阻断后续（Template 步骤有 try/except）。
        """
        self._ensure_index()
        self._update_mapping()
        self._ensure_qa_index()
        self._ensure_template()

    def _ensure_index(self):
        """
        业务功能：确保主文档索引和元数据索引存在，不存在则按标准 Mapping 创建。
        关键流程：先检查 kb_document_v1，再检查 kb_doc_meta，均为幂等操作。
        """
        if not self.es.indices.exists(index=INDEX_NAME):
            print(f"⚠️ 索引 {INDEX_NAME} 不存在，正在自动创建...")
            mapping = {
                "settings": {
                    # 单节点部署：1 主分片，0 副本（避免 yellow 状态告警）
                    # 扩容时可热更新 number_of_replicas，无需重建索引
                    "number_of_shards": 1,
                    "number_of_replicas": 0
                },
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
                            "similarity": "cosine"
                        },
                        "keywords": {"type": "keyword"},
                        "metadata": {
                            "properties": {
                                "source": {"type": "keyword"},
                                "chunk_id": {"type": "integer"},
                                "is_latest": {"type": "boolean"},
                                "data_source": {"type": "keyword"},
                                "owner_dept_id": {"type": "keyword"},
                                "visible_depts": {"type": "keyword"},
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
            self.es.indices.create(index=DOC_META_INDEX, body={
                "mappings": {
                    "properties": {
                        "source_name":  {"type": "keyword"},
                        "content_hash": {"type": "keyword"},
                        "doc_version":  {"type": "integer"},
                        "is_latest":    {"type": "boolean"},
                        "updated_by":   {"type": "keyword"},
                        "version_at":   {"type": "date", "format": "epoch_millis"},
                        "chunk_count":  {"type": "integer"},
                        "visibility":   {"type": "keyword"},
                        "acl_tokens":   {"type": "keyword"}
                    }
                }
            })
            print(f"✅ 元数据索引 {DOC_META_INDEX} 创建成功！")

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
                        "display_content":   {"type": "text", "index": False},  # [P0-5A] 含面包屑的展示内容，不建倒排索引
                        "chunk_granularity": {"type": "keyword"},
                        "parent_chunk_id":   {"type": "keyword"},
                        "sparse_vector":     {"type": "rank_features"},
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

    def _ensure_qa_index(self):
        """
        业务功能：确保 Q&A 索引 kb_qa_pairs 存在。
        Mapping 设计：question(文本+向量) + answer_content(条文原文) + 源字段
        """
        if self.es.indices.exists(index=QA_INDEX_NAME):
            return
        print(f"⚠️ 创建 Q&A 索引 {QA_INDEX_NAME}...")
        self.es.indices.create(index=QA_INDEX_NAME, body={
            "mappings": {
                "properties": {
                    "question":        {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                    "question_vector": {"type": "dense_vector", "dims": 1024, "index": True, "similarity": "cosine"},
                    "answer_content":  {"type": "text"},
                    "answer_chunk_id": {"type": "keyword"},
                    "section_path":    {"type": "keyword"},
                    "source":          {"type": "keyword"},
                    "is_latest":       {"type": "boolean"}
                }
            }
        })
        print(f"✅ Q&A 索引 {QA_INDEX_NAME} 创建成功！")

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
                            "number_of_shards": 1,
                            "number_of_replicas": 0,
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
