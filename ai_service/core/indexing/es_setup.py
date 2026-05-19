import os
import time
import requests as _http
from elasticsearch import Elasticsearch

# docker中执行脚本如下：
# docker-compose run --rm -e ES_SETUP_MODE=init ai-service python scripts/es_init.py

# ── ES 索引名称常量（全系统统一入口，避免魔法字符串）──────────────────────────
# [架构重构] 从 rag_pipeline.py 提取，作为单一数据源，所有模块统一从此导入
INDEX_NAME     = "kb_document_v1"
QA_INDEX_NAME  = "kb_qa_pairs"       # QA 物理索引名（仅 init 创建时使用）
DOC_META_INDEX = "kb_doc_meta"

# ── QA 索引读写别名（[轨道A] 别名化改造，业务代码统一使用别名不直接引用物理索引）─
# 设计：写别名 is_write_index=True 保证 bulk 精确路由；读别名无限制支持 Reindex 期间多索引并读
QA_INDEX_WRITE_ALIAS = "kb_qa_write"  # 所有写入（bulk index）走此别名
QA_INDEX_READ_ALIAS  = "kb_qa_read"   # 所有读查询（KNN、match）走此别名


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

        # 2. 固定追加 QA 索引（不在路由表中，单独管理）
        active_indices.add(QA_INDEX_NAME)

        # 3. 逐一校验索引是否存在
        missing = [idx for idx in active_indices if not self.es.indices.exists(index=idx)]
        if missing:
            raise RuntimeError(
                f"[ESSetup] ❌ 以下索引在 ES 中不存在，服务拒绝启动！\n"
                f"  缺失索引: {missing}\n"
                f"  解决方案: 以 ES_SETUP_MODE=init 运行 scripts/es_init.py 创建缺失索引"
            )

        # 4. 校验 kb_qa_pairs.question_vector 字段类型（防止 ES 动态推断污染）
        try:
            mapping = self.es.indices.get_mapping(index=QA_INDEX_NAME)
            qv_type = (
                mapping.get(QA_INDEX_NAME, {})
                .get("mappings", {}).get("properties", {})
                .get("question_vector", {}).get("type", "")
            )
            if qv_type and qv_type != "dense_vector":
                raise RuntimeError(
                    f"[ESSetup] ❌ {QA_INDEX_NAME}.question_vector 类型为 '{qv_type}'（期望 dense_vector）。\n"
                    f"  此索引需要 Reindex 重建，禁止自动删除！\n"
                    f"  Reindex 步骤:\n"
                    f"    1. 以新名创建正确 Mapping 的索引（如 kb_qa_pairs_v2）\n"
                    f"    2. 执行 POST _reindex：src=kb_qa_pairs, dest=kb_qa_pairs_v2\n"
                    f"    3. 原子切换别名指向 kb_qa_pairs_v2\n"
                    f"    4. 确认无误后删除旧索引 kb_qa_pairs"
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
                "question_vector": {"type": "dense_vector", "dims": 1024, "index": True, "similarity": "cosine"},
                "answer_content":  {"type": "text"},
                "answer_chunk_id": {"type": "keyword"},
                "section_path":    {"type": "keyword"},
                "source":          {"type": "keyword"},
                "doc_version":     {"type": "integer"},  # [版本化] 供 registerDoc 2PC 精确版本匹配和 is_latest 切换
                "is_latest":       {"type": "boolean"}
            }
        }

        if self.es.indices.exists(index=QA_INDEX_NAME):
            # init 模式：索引已存在则直接返回，严禁任何删除或修改操作
            print(f"ℹ️ [ESSetup] {QA_INDEX_NAME} 已存在，跳过创建")
            # 幂等补注alias（兼容已有环境首次升级的场景）
            self._ensure_qa_aliases()
            return

        print(f"[ESSetup] 创建 Q&A 索引 {QA_INDEX_NAME}...")
        self.es.indices.create(index=QA_INDEX_NAME, body={"mappings": _target_mapping})
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
        actions = []
        try:
            existing_aliases = self.es.indices.get_alias(index=QA_INDEX_NAME)
            current = existing_aliases.get(QA_INDEX_NAME, {}).get("aliases", {})
        except Exception:
            current = {}

        if QA_INDEX_WRITE_ALIAS not in current:
            actions.append({"add": {
                "index": QA_INDEX_NAME,
                "alias": QA_INDEX_WRITE_ALIAS,
                "is_write_index": True
            }})
        if QA_INDEX_READ_ALIAS not in current:
            actions.append({"add": {
                "index": QA_INDEX_NAME,
                "alias": QA_INDEX_READ_ALIAS
            }})

        if not actions:
            print(f"ℹ️ [ESSetup] QA 别名已存在，跳过注册")
            return

        self.es.indices.update_aliases(body={"actions": actions})
        alias_names = [a["add"]["alias"] for a in actions]
        print(f"✅ [ESSetup] QA 别名注册完成: {alias_names} → {QA_INDEX_NAME}")


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
        partition_indices_with_meta = partition_indices + [DOC_META_INDEX]

        for phys_index in partition_indices_with_meta:
            # 写别名命名规则：{物理索引名}_write
            write_alias = f"{phys_index}_write"
            try:
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

