import os
import torch
import time
from sentence_transformers import SentenceTransformer
from core.chunking.semantic_chunker import SemanticChunker
from elasticsearch import Elasticsearch, helpers
from markitdown import MarkItDown
import concurrent.futures
from tqdm import tqdm
import hashlib
import jieba.analyse
import re
import threading
from core.model_manager import model_manager

# 全局保护锁：防止高并发上传 .doc 文件时大批量触发 COM RPC 实例化崩溃
_doc_com_lock = threading.Lock()

# 配置参数（P1-6 修复：完全环境变量注入，解除本机路径绑定）
MODEL_PATH  = os.getenv("MODEL_PATH",  "/app/models/onnx_native/bge-m3")
ES_HOST     = os.getenv("ES_HOST",     "http://elasticsearch:9200")
ES_USER     = os.getenv("ES_USER",     "")
ES_PASS     = os.getenv("ES_PASS",     "")
INDEX_NAME  = "kb_document_v1"
QA_INDEX_NAME  = "kb_qa_pairs"
DOC_META_INDEX = "kb_doc_meta"
DEVICE      = "cuda" if torch.cuda.is_available() else "cpu"

class RAGPipeline:
    def __init__(self):
        # [架构重整] 不再独立加载 SentenceTransformer，直接复用主进程已加载的 ONNX model_manager。
        if model_manager.model is None:
            print("[RAGPipeline] model_manager 未就绪，独立加载中...")
            model_manager.load_model()
        # [P1-6 修复] ES 客户端支持 Basic Auth（生产环境通过 ES_USER/ES_PASS 环境变量配置）
        es_kwargs = {"hosts": [ES_HOST]}
        if ES_USER:
            es_kwargs["basic_auth"] = (ES_USER, ES_PASS)
        self.es = Elasticsearch(**es_kwargs)
        self._ensure_index_exists()
        self._update_mapping()
        self.splitter = SemanticChunker(model_manager.cfg if hasattr(model_manager, 'cfg') else {})
        self.md_converter = MarkItDown()
        self._ensure_qa_index_exists()
        self._ensure_index_template()
        self.ai_host = os.getenv("AI_SERVICE_HOST", "http://localhost:8001") # AI 本身，由于是供自身调用
        # [P1-7] Java 服务内部接口地址（用于获取原子版本号）
        self.java_host = os.getenv("JAVA_SERVICE_HOST", "http://knowledge-base-java:8080")
        self._internal_token = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")


    def _ensure_index_exists(self):
        """确保 ES 索引存在，如果不存在则自动创建并配置 mapping"""
        if not self.es.indices.exists(index=INDEX_NAME):
            print(f"⚠️ 索引 {INDEX_NAME} 不存在，正在自动创建...")
            mapping = {
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
                                "dynamic_meta": {
                                    "type": "object",
                                    "dynamic": True
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
        # 确保 kb_doc_meta 索引也存在
        if not self.es.indices.exists(index=DOC_META_INDEX):
            self.es.indices.create(index=DOC_META_INDEX, body={
                "mappings": {
                    "properties": {
                        "source_name":    {"type": "keyword"},
                        "content_hash":   {"type": "keyword"},
                        "doc_version":    {"type": "integer"},
                        "is_latest":      {"type": "boolean"},
                        "updated_by":     {"type": "keyword"},
                        "version_at":     {"type": "date", "format": "epoch_millis"},
                        "chunk_count":    {"type": "integer"},
                        "visibility":     {"type": "keyword"}
                    }
                }
            })
            print(f"✅ 元数据索引 {DOC_META_INDEX} 创建成功！")

    def _update_mapping(self):
        """
        为已有索引热更新 mapping，追加 Phase 1 新增字段定义（幂等，存在则忽略）。
        新增：版本控制字段（doc_version/version_at/updated_by）、
              部门编码分级字段（dept_l2/l4/l6/l9/dept_code_full）、
              权限字段（visibility/access_groups）、标签（tags keyword 版）。
        """
        try:
            self.es.indices.put_mapping(
                index=INDEX_NAME,
                body={
                    "properties": {
                        "chunk_granularity": {"type": "keyword"},
                        "parent_chunk_id":   {"type": "keyword"},
                        "sparse_vector":     {"type": "rank_features"},
                        "colloquial_vector": {
                            "type": "dense_vector",
                            "dims": 1024,
                            "index": True,
                            "similarity": "cosine"
                        },
                        "acl_tokens": {
                            "type": "keyword"
                        },
                        "metadata": {
                            "properties": {
                                "document_number": {"type": "keyword"},
                                "section_path":    {"type": "keyword"},
                                "chunk_type":      {"type": "keyword"},
                                "quality_score":   {"type": "float"},
                                "dynamic_meta":    {"type": "object", "dynamic": True},
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
                                "visibility":     {"type": "keyword"},
                                "access_groups":  {"type": "keyword"},
                                "uploader_id":    {"type": "keyword"},
                                "handler_user_ids":  {"type": "keyword"},
                                "handler_dept_l6":   {"type": "keyword"},
                                "tags_kw":        {"type": "keyword"},
                            }
                        }
                    }
                }
            )
            print(f"✅ [Mapping] Phase 1 新增字段（版本/部门/权限）已热更新到索引 {INDEX_NAME}")
        except Exception as e:
            print(f"⚠️ [Mapping] 更新 mapping 失败（可能索引尚未创建或冲突）: {e}")

    def _ensure_qa_index_exists(self):
        """
        确保 Q&A 索引 kb_qa_pairs 存在。
        mapping 设计：question(文本+向量) + answer_content(条文原文) + 源字段
        """
        if self.es.indices.exists(index=QA_INDEX_NAME):
            return
        print(f"⚠️ 创建 Q&A 索引 {QA_INDEX_NAME}...")
        self.es.indices.create(index=QA_INDEX_NAME, body={
            "mappings": {
                "properties": {
                    "question":    {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
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

    def _ensure_index_template(self):
        """
        业务功能：向 ES 注册 Index Template，确保 kb_document_* 系列索引统一继承标准 mapping。
        关键流程：
          1. 注册/更新名为 kb_document_template 的 Index Template（幂等）
          2. Template 覆盖 kb_document_* 通配符，新建索引时自动套用 mapping 和 alias
          3. 同时为 kb_document_v1 添加 kb_document 别名（如未添加），方便 Java 侧无缝切换索引版本
        此方法在服务启动时调用一次，后续无需重复注册。
        """
        try:
            # 注册模板（put_template 是幂等的，已存在时覆盖更新）
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
                                    "ik_smart": {"type": "custom", "tokenizer": "ik_smart"},
                                    "ik_max_word": {"type": "custom", "tokenizer": "ik_max_word"}
                                }
                            }
                        },
                        "mappings": {
                            "properties": {
                                "content":  {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                                "vector":   {"type": "dense_vector", "dims": 1024, "index": True, "similarity": "cosine"},
                                "sparse_vector":     {"type": "rank_features"},
                                "colloquial_vector": {"type": "dense_vector", "dims": 1024, "index": True, "similarity": "cosine"},
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
                                        "access_groups":{"type": "keyword"},
                                        "uploader_id":  {"type": "keyword"},
                                        "tags_kw":      {"type": "keyword"},
                                        "tags":         {"type": "text"},
                                        "quality_score":{"type": "float"},
                                        "data_source":  {"type": "keyword"},
                                        "owner_dept_id":{"type": "keyword"},
                                        "publish_time": {"type": "date", "format": "yyyy-MM-dd||epoch_millis"},
                                        "document_number": {"type": "keyword"},
                                        "dynamic_meta": {"type": "object", "dynamic": True},
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

    @staticmethod
    def _compute_dept_levels(dept_code: str) -> dict:
        """
        业务功能：将 12 位行政区划编码拆解为各行政层级前缀，存入 ES 供权限过滤使用。
        规则（GB/T 2260）：前2位=省、前4位=市、前6位=区县、前9位=乡镇、全12位=具体机构。
        入参为空时默认返回全局兜底值，确保存量文档可见性不受影响。
        """
        if not dept_code or not str(dept_code).strip():
            # 无部门信息时写入兜底值，SearchService 的 INTERNAL 逻辑会放行这类文档
            return {"dept_l2": None, "dept_l4": None, "dept_l6": None,
                    "dept_l9": None, "dept_code_full": None}
        code = str(dept_code).strip()
        return {
            "dept_l2":        code[:2]  if len(code) >= 2  else code,
            "dept_l4":        code[:4]  if len(code) >= 4  else code,
            "dept_l6":        code[:6]  if len(code) >= 6  else code,
            "dept_l9":        code[:9]  if len(code) >= 9  else code,
            "dept_code_full": code,
        }

    @staticmethod
    def _compute_acl_tokens(visibility: str, dept_code: str, uploader_id: str, source_name: str) -> list:
        """
        业务功能：根据文档可见度计算写入 ES 的 ACL Token 列表（Python 侧兜底实现）。
        与 Java DocIngestService.computeAclTokens() 保持完全一致的 Token 生成规则，
        用于覆盖 SFTP 批量导入 / 命令行脚本等不经过 Java DocIngestService 的旁路场景。

        token 格式约定（与 Java 侧 AclTokenBuilder 对称）：
          _PUBLIC          → 公开文档，任何人（含匿名）可访问
          _INTERNAL        → 内部文档，任意已登录用户可访问
          DEPT:{dept_code} → 部门文档，该部门成员及父级部门成员可访问
          USER:{uid}       → 上传者本人始终可访问
          DOC:{name}       → GRANT 文档的访问凭证

        :param visibility:  文档可见度枚举字符串（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT）
        :param dept_code:   部门编码（visibility=DEPT 时有效）
        :param uploader_id: 上传者用户 ID
        :param source_name: 文件原始名称（与 ES metadata.source 一致）
        :return: ACL Token 列表
        """
        tokens = []
        vis = (visibility or "INTERNAL").upper()

        if vis == "PUBLIC":
            tokens.append("_PUBLIC")
        elif vis == "INTERNAL":
            tokens.append("_INTERNAL")
            if uploader_id:
                tokens.append(f"USER:{uploader_id}")
        elif vis == "DEPT":
            if dept_code:
                tokens.append(f"DEPT:{dept_code}")
            if uploader_id:
                tokens.append(f"USER:{uploader_id}")
        elif vis == "PRIVATE":
            if uploader_id:
                tokens.append(f"USER:{uploader_id}")
        elif vis == "GRANT":
            # 授权访问凭证：上传者本人 + DOC:{source_name} 凭证（被授权用户持有此 token）
            if uploader_id:
                tokens.append(f"USER:{uploader_id}")
            if source_name:
                tokens.append(f"DOC:{source_name}")
        else:
            # 未知可见度：最严格的私有处理
            if uploader_id:
                tokens.append(f"USER:{uploader_id}")

        return tokens



    def _get_next_version(self, source_name: str) -> int:
        """
        业务功能：获取文档下一版本号。
        [P1-7 修复] 改为调用 Java Registry 接口，由 MySQL 事务保证原子性，消除原来从 ES
        读取最大版本号的 TOCTOU 竞争问题（并发上传时可能产生相同版本号）。
        递归安全：需要携带有效内部 Token，调用失败时降级为 1。
        """
        try:
            import requests as _rq
            resp = _rq.get(
                f"{self.java_host}/api/v1/internal/doc/next-version",
                params={"sourceName": source_name},
                headers={"X-Internal-Token": self._internal_token},
                timeout=5.0
            )
            if resp.status_code == 200:
                data = resp.json().get("data", 1)
                return int(data)
            else:
                print(f"⚠️ [P1-7 Version] Java 接口返回异常: {resp.status_code}，降级为 1")
                return 1
        except Exception as e:
            print(f"⚠️ [P1-7 Version] 调用 Java 版本号接口失败，降级为 1: {e}")
            return 1

    def _extract_dynamic_meta(self, text):
        """基于 Vue 动态下发的高容错正则表达式提取大字典"""
        meta = {}
        if not text: return meta
        
        rules_str = model_manager.cfg.get('metaExtractRules') if hasattr(model_manager, 'cfg') else None
        rules = []
        if isinstance(rules_str, str):
            import json
            try: rules = json.loads(rules_str)
            except: pass
        elif isinstance(rules_str, list):
            rules = rules_str

        if not rules:
            return meta
            
        # 沙盒防线：只扫描前 1500 个字符，防止重度 ReDoS 致死
        header_text = text[:1500]
        
        for rule in rules:
            key = rule.get('key')
            regex = rule.get('regex')
            if key and regex:
                try:
                    m = re.search(regex, header_text)
                    if m and len(m.groups()) > 0:
                        meta[key] = str(m.group(1)).strip()
                except Exception as e:
                    print(f"⚠️ 动态正则匹配异常 (Key={key}): {e}")
                    
        return meta

    @staticmethod
    def _clean_raw_text(text: str) -> str:
        """
        业务功能：文本进入切片器前的预净化门控。
        规则1 - 水印行：某汉字连续出现 ≥4 次（内内内内）→ 删除该行
        规则2 - 水印表格行：Markdown 表格行中所有单元格均为同一个汉字 → 删除
        规则3 - 高频重复行：连续 ≥3 行完全相同 → 折叠为 1 行
        规则4 - 乱码行：行内 Unicode 替换字符(U+FFFD)占比 >30% → 视为乱码删除
        规则5 - 内容门控：净化后有效汉字/字母 < 50 字符 → 返回空串拒绝入库
        """
        if not text:
            return text

        import re as _re
        lines = text.splitlines()
        cleaned: list = []
        prev_line: str = None
        repeat_count: int = 0

        _watermark_re = _re.compile(r'([\u4e00-\u9fa5])\1{3,}')
        _table_sep_re  = _re.compile(r'^\s*\|[\s\-\|]+\|\s*$')

        for line in lines:
            stripped = line.strip()
            if not stripped:
                prev_line = stripped
                repeat_count = 0
                continue

            # 规则1：水印行
            if _watermark_re.search(stripped):
                continue

            # 规则2：水印 Markdown 表格行
            if stripped.startswith('|') and stripped.endswith('|'):
                if _table_sep_re.match(stripped):
                    continue
                cells = [c.strip() for c in stripped.split('|') if c.strip() and c.strip() != '---']
                if cells and all(len(c) == 1 and '\u4e00' <= c <= '\u9fa5' for c in cells):
                    if len(set(cells)) == 1:
                        continue

            # 规则3：高频重复行折叠
            if stripped == prev_line:
                repeat_count += 1
                if repeat_count >= 2:
                    continue
            else:
                repeat_count = 0

            # 规则4：乱码行（替换字符占比 >30%）
            replacement_count = stripped.count('\ufffd')
            if len(stripped) > 5 and replacement_count / len(stripped) > 0.30:
                continue

            prev_line = stripped
            cleaned.append(line)

        result = '\n'.join(cleaned)

        # 规则5：内容门控
        effective_chars = _re.sub(r'[^\u4e00-\u9fa5a-zA-Z0-9]', '', result)
        if len(effective_chars) < 50:
            return ''

        return result

    @staticmethod
    def _clean_antiword_output(raw: str) -> str:
        """
        业务功能：清洗 antiword 对 OLE .doc（特别是政府红头文件）的输出噪声。
        根因：antiword 对 Word 内部二进制流（字体表、对象描述、样式定义）
             以 errors='replace' 解码，产生大量 U+FFFD 替换字符和低密度噪声行。
        清洗规则：
          1. 移除 Unicode 替换字符（U+FFFD）和 ASCII 控制字符
          2. 折叠连续空行（3+ 个 \n → 1个）
          3. 移除低密度噪声行：<60字符且中文/数字占比<15%（字体表、对象ID）
          4. 保留红头文件有价値内容：标题/文号/正文/日期
        """
        import re as _re2
        # 规则•1：去除替换字符和控制字符
        cleaned = raw.replace('\ufffd', '')
        cleaned = _re2.sub(r'[\x00-\x08\x0b-\x0c\x0e-\x1f]', '', cleaned)
        # 规则•2：折叠空白爆炸
        cleaned = _re2.sub(r'\n{3,}', '\n\n', cleaned)
        cleaned = _re2.sub(r'[ \t]{4,}', '  ', cleaned)
        # 规则•3：過滤低密度噪声行
        result_lines = []
        for line in cleaned.splitlines():
            stripped = line.strip()
            if not stripped:
                result_lines.append('')
                continue
            length = len(stripped)
            if length < 60:
                cn_count = sum(1 for c in stripped if '\u4e00' <= c <= '\u9fa5'
                               or c in '\uff0c\u3002\uff01\uff1f\u3001\uff1b\uff1a\u300a\u300b\u3010\u3011\uff08\uff09\u2014\u2026')
                num_count = sum(1 for c in stripped if c.isdigit())
                useful = cn_count + num_count
                if useful / max(length, 1) < 0.15 and '\u53d1' not in stripped and '\u53f7' not in stripped:
                    continue
            result_lines.append(line)
        result = '\n'.join(result_lines).strip()
        return _re2.sub(r'\n{3,}', '\n\n', result)

    def extract_text(self, file_path):
        """
        业务功能：将本地文件路径或 MinIO 预签名 URL 转换为纯文本内容。
        关键流程：
          1. 若 file_path 是预签名 URL -> 先 requests 下载到本地临时文件
          2. 针对 .doc (OLE格式，旧版 Word 97-2003)：
               - A路：调用系统 antiword 命令（Dockerfile 已安装 antiword）
               - B路：python-docx 兼容读取（部分 .doc 实为 Open XML 格式）
               - C路：字节流 UTF-16LE 暴力提取可打印字符（最后保底）
          3. 其他格式：直接交给 MarkItDown 处理
          4. 所有路径均有 try-except 兜底，绝不因解析失败阻塞整个任务队列
        """
        import tempfile
        temp_local = None
        try:
            local_path = file_path

            # 步骤1：若是 HTTP URL，先下载到本地临时文件
            if file_path.startswith("http://") or file_path.startswith("https://"):
                import requests as _rq
                try:
                    resp = _rq.get(file_path, timeout=60, stream=True)
                    resp.raise_for_status()
                    # 从 URL 路径推断扩展名（忽略预签名参数）
                    raw_suffix = file_path.split("?")[0].rsplit(".", 1)
                    suffix = f".{raw_suffix[-1].lower()}" if len(raw_suffix) > 1 else ".bin"
                    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
                        for chunk in resp.iter_content(chunk_size=65536):
                            tmp.write(chunk)
                        temp_local = tmp.name
                    local_path = temp_local
                    print(f"[extract_text] URL 已下载到临时文件: {temp_local} (ext={suffix})")
                except Exception as dl_err:
                    print(f"⚠️ [extract_text] URL 下载失败，尝试直接传递给 MarkItDown: {dl_err}")
                    local_path = file_path  # 回退

            # ─── 步骤2：.doc 信封格式解析（5种实际格式共用同一扩展名）─────────────────
            # 根因：antiword 只处理 OLE/BIFF8，RTF/HTML/ZIP 会被拒绝且履码不受保护。
            # 修复：先读魔数字节识别真实格式 → 各格式专属解析路径 → chardet 编码检测防乱码。
            if local_path.lower().endswith(".doc") and not local_path.startswith("http"):
                import chardet as _chardet
                import re as _re

                # ── 魔数检测：确定 .doc 真实格式 ──────────────────────────────────────────
                _doc_format = "unknown"
                try:
                    with open(local_path, "rb") as _f:
                        _magic = _f.read(16)
                    if _magic[:4] == b"\xd0\xcf\x11\xe0":
                        _doc_format = "ole"
                    elif _magic[:5] == b"{\\rtf":
                        _doc_format = "rtf"
                    elif _magic[:2] == b"PK":
                        _doc_format = "zip"
                    elif b"<html" in _magic.lower() or b"<!doc" in _magic.lower():
                        _doc_format = "html"
                    if _doc_format == "unknown":
                        with open(local_path, "r", encoding="utf-8", errors="ignore") as _tf:
                            _head = _tf.read(20)
                        if "{\\rtf" in _head or "{\\RTF" in _head:
                            _doc_format = "rtf"
                except Exception as _me:
                    print(f"⚠️ [extract_text] 魔数检测失败: {_me}")
                print(f"[extract_text] .doc 真实格式: {_doc_format} ← {local_path}")

                # ── RTF 路径（WPS/政府环境最常见）──────────────────────────────────────────
                if _doc_format == "rtf":
                    try:
                        with open(local_path, "rb") as _f:
                            _rtf_bytes = _f.read()
                        _enc = (_chardet.detect(_rtf_bytes[:2000]).get("encoding") or "utf-8")
                        _rtf_content = _rtf_bytes.decode(_enc, errors="replace")
                        try:
                            from striprtf.striprtf import rtf_to_text
                            _text = rtf_to_text(_rtf_content)
                        except ImportError:
                            _text = _re.sub(r"\\[a-z]+\-?\d*\s?", " ", _rtf_content)
                            _text = _re.sub(r"[{}]", "", _text)
                            _text = _re.sub(r"\s{2,}", "\n", _text)
                        if _text and _text.strip():
                            print(f"[extract_text] RTF 解析成功 ({len(_text)} 字符, enc={_enc})")
                            return _text.strip()
                    except Exception as _rtf_err:
                        print(f"⚠️ [extract_text] RTF 解析异常: {_rtf_err}")

                # ── ZIP/.docx 路径 ──────────────────────────────────────────────────────
                elif _doc_format == "zip":
                    try:
                        import docx as _docx
                        _doc_obj = _docx.Document(local_path)
                        _text = "\n".join(p.text for p in _doc_obj.paragraphs if p.text.strip())
                        if _text.strip():
                            print(f"[extract_text] python-docx 解析 ZIP-doc 成功 ({len(_text)} 字节)")
                            return _text
                    except Exception as _ze:
                        print(f"⚠️ [extract_text] ZIP-doc 解析失败: {_ze}")

                # ── HTML 路径 ───────────────────────────────────────────────────────────
                elif _doc_format == "html":
                    try:
                        with open(local_path, "rb") as _hf:
                            _hbytes = _hf.read()
                        _enc = (_chardet.detect(_hbytes[:2000]).get("encoding") or "utf-8")
                        _html_content = _hbytes.decode(_enc, errors="replace")
                        try:
                            from bs4 import BeautifulSoup
                            _text = BeautifulSoup(_html_content, "html.parser").get_text(separator="\n", strip=True)
                        except ImportError:
                            _text = _re.sub(r"<[^>]+>", " ", _html_content)
                            _text = _re.sub(r"\s{2,}", "\n", _text).strip()
                        if _text:
                            return _text
                    except Exception as _he:
                        print(f"⚠️ [extract_text] HTML-doc 解析失败: {_he}")

                # ── OLE/unknown 路径（antiword → olefile → brute force）──────────────
                if _doc_format in ("ole", "unknown"):
                    # A路：antiword（bytes 模式 + chardet 解码，避免 GBK→UTF-8 乱码）
                    try:
                        import subprocess
                        _aw_result = subprocess.run(
                            ["antiword", local_path],
                            capture_output=True, timeout=30
                        )
                        if _aw_result.returncode == 0 and _aw_result.stdout:
                            _aw_enc = (_chardet.detect(_aw_result.stdout[:2000]).get("encoding") or "utf-8")
                            _aw_text_raw = _aw_result.stdout.decode(_aw_enc, errors="replace")
                            if _aw_text_raw.strip():
                                # [噪声清洗] 政府红头文件 300 字可产生 10 万字节垃圾输出，清洗后再返回
                                _aw_text = self._clean_antiword_output(_aw_text_raw)
                                print(f"[extract_text] antiword 成功 "
                                      f"(原始={len(_aw_text_raw)} → 清洗后={len(_aw_text)} 字节, enc={_aw_enc})")
                                return _aw_text
                        else:
                            print(f"⚠️ [extract_text] antiword 错误: {_aw_result.stderr[:200]}")
                    except FileNotFoundError:
                        print("⚠️ [extract_text] antiword 未安装，尝试 olefile 降级")
                    except Exception as _aw_err:
                        print(f"⚠️ [extract_text] antiword 异常: {_aw_err}")

                    # B路：python-docx 兼容
                    try:
                        import docx
                        doc_obj = docx.Document(local_path)
                        text = "\n".join(p.text for p in doc_obj.paragraphs)
                        if text.strip():
                            print(f"[extract_text] python-docx 兼容读取成功 ({len(text)} 字节)")
                            return text
                    except Exception as docx_err:
                        print(f"⚠️ [extract_text] python-docx 无法读取: {docx_err}")

                    # C路：olefile WordDocument 流（UTF-16LE）
                    try:
                        import olefile as _ole
                        if _ole.isOleFile(local_path):
                            ole = _ole.OleFileIO(local_path)
                            if ole.exists("WordDocument"):
                                raw = ole.openstream("WordDocument").read()
                                text_raw = raw[0x80:].decode("utf-16-le", errors="replace")
                                readable = _re.sub(r'[^\u4e00-\u9fa5\w\s，。！？、；：《》]', " ", text_raw)
                                readable = _re.sub(r'\s{3,}', "\n", readable).strip()
                                ole.close()
                                if readable and len(readable) > 20:
                                    print(f"[extract_text] olefile 提取成功: {len(readable)} 字符")
                                    return readable
                            ole.close()
                    except ImportError:
                        pass
                    except Exception as ole_err:
                        print(f"⚠️ [extract_text] olefile 异常: {ole_err}")

                    # D路兜底：chardet 检测编码 + 暴力提取
                    try:
                        with open(local_path, "rb") as _bf:
                            _raw_bytes = _bf.read()
                        _enc = _chardet.detect(_raw_bytes[:4096]).get("encoding") or "utf-16-le"
                        _raw_text = _raw_bytes.decode(_enc, errors="replace")
                        readable = _re.sub(r'[^\u4e00-\u9fa5\w\s，。]', " ", _raw_text)
                        readable = _re.sub(r'\s{3,}', "\n", readable).strip()
                        if readable and len(readable) > 50:
                            print(f"⚠️ [extract_text] 暴力提取 (enc={_enc}): {len(readable)} 字符")
                            return readable
                    except Exception as _raw_err:
                        print(f"⚠️ [extract_text] 暴力提取失败: {_raw_err}")

                return f"【.doc 格式解析失败】：检测到真实格式为 {_doc_format}，所有解析路径均失败。请将文件另存为 .docx 或 .pdf 重新上传。"

            # 步骤3：其他格式，交给 MarkItDown 处理
            try:
                result = self.md_converter.convert(local_path)
                return result.text_content
            except Exception as convert_error:
                print(f"⚠️ [MarkItDown] 底层解析引擎拦截到文件污损或异常: {convert_error}")
                return f"【内容提取受限】：文件流受损或遇到无法解析的加密拦截（报错提示：{str(convert_error)}）。系统已安全跳过正文抽取，您可以尝试修复受损文件后重新覆盖上传。"

        except Exception as e:
            print(f"⚠️ [Pipeline] 解析包装层触发不可控异常: {e}")
            return f"【系统脱机保护】：前置转码遭遇致命异常（报错提示：{str(e)}）。"
        finally:
            if temp_local and os.path.exists(temp_local):
                try:
                    os.remove(temp_local)
                except Exception:
                    pass


    def process_and_index(self, file_path, original_name=None, ext_metadata=None):
        """核心处理链路：解析 -> 切片 -> 向量化 -> 入库"""
        print(f"Processing: {file_path}")
        
        # 1. 解析与转码
        t0 = time.time()
        raw_text = self.extract_text(file_path)

        # [净化门控] 过滤水印/乱码/重复行，净化后内容<50字符则拒绝入库
        raw_text = self._clean_raw_text(raw_text or '')
        if not raw_text:
            print(f"⚠️ [{os.path.basename(file_path)}] 净化后内容为空（水印/乱码），拒绝入库")
            return {"status": "skipped", "reason": "empty_after_cleaning"}

        parse_result = "OK"
        if raw_text.startswith("【内容提取受限】"):
            parse_result = raw_text[:200]
        elif raw_text.startswith("【文件降级解析失败】"):
            parse_result = raw_text[:200]

        
        # --- 新增：关键字提取 (取文章前 3000 字提取 Top 10) ---
        analysis_text = raw_text[:3000] if raw_text else ""
        keywords = jieba.analyse.extract_tags(analysis_text, topK=10)
        
        # --- 增维战列舰：热挂载动态正则抓手机制 ---
        doc_meta = self._extract_dynamic_meta(raw_text)
        if doc_meta:
            print(f"[{os.path.basename(file_path)}] 成功命活动态特征列: {doc_meta}")
            for k, val in doc_meta.items():
                if val and val not in keywords:
                    keywords.append(val)
        
        t1 = time.time()
        print(f"[{os.path.basename(file_path)}] 转换 Markdown 并提取关键字 {keywords} 耗时: {t1 - t0:.3f} 秒")
        
        # 2. 纯 Python 篇章级切片逻辑
        chunks = self.splitter.process_document(raw_text)
        t2 = time.time()
        print(f"[{os.path.basename(file_path)}] 语义切片耗时: {t2 - t1:.3f} 秒 (切片数量: {len(chunks)})")
        
        actions = []
        source_name = original_name if original_name else os.path.basename(file_path)
        ext_metadata = ext_metadata or {}

        # [A4] 内容哈希去重：避免同内容不同名文件造成索引冗余
        # 取正文前 2000 字计算 MD5，与 kb_doc_meta 中已有的 content_hash 对比
        # force_reindex=True 时强制重建（管理员手动触发）
        raw_sample = (raw_text or "")[:2000]
        # [修复] 优先使用 Java 上传时预计算的 SHA-256 hash（文件二进制前8KB），
        # 与 Java 侧 existsByContentHash 查询使用同一 hash，确保去重链路完整。
        # 仅在 Java 未传入 hash（如批量导入/SFTP 场景）时才降级到 MD5 文本 hash。
        content_hash = ext_metadata.get("content_hash") if ext_metadata and ext_metadata.get("content_hash") else \
                       hashlib.md5(raw_sample.encode("utf-8", errors="ignore")).hexdigest()
        force_reindex = ext_metadata.get("force_reindex", False)

        if not force_reindex:
            try:
                dup_resp = self.es.search(
                    index=DOC_META_INDEX,
                    body={
                        "query": {
                            "bool": {
                                "must": [
                                    {"term": {"content_hash": content_hash}},
                                    {"term": {"is_latest": True}}
                                ],
                                "must_not": [
                                    # 允许同名文档更新（只跳过不同名的重复内容）
                                    {"term": {"source_name.keyword": source_name}}
                                ]
                            }
                        },
                        "_source": ["source_name"],
                        "size": 1
                    },
                    ignore_unavailable=True
                )
                hits = dup_resp.get("hits", {}).get("hits", [])
                if hits:
                    existing = hits[0]["_source"].get("source_name", "unknown")
                    print(f"⚠️ [A4 ContentDedup] '{source_name}' 与已有文档 '{existing}' 内容指纹相同，跳过重复入库。")
                    return {"status": "skipped", "reason": "duplicate_content", "existing": existing}
            except Exception as e:
                # 去重查询失败时不阻塞主流程，继续正常入库
                print(f"⚠️ [A4 ContentDedup] 去重查询失败（已跳过）: {e}")

        file_base_hash = hashlib.md5(source_name.encode('utf-8')).hexdigest()

        # --- [P2-2 修复] 删除第一次冗余的 process_document 调用 ---
        # 根因：原代码调用了两次 splitter.process_document(raw_text)，第一次结果未使用，
        #        第二次才分山粗细粒度；对大文档产生 2x 内存 + 2x 切片开销。
        # 此转振直接跳到双粒度切片，保留原有逻辑不变。

        # --- 版本过期控制：将 ES 中所有同名老版本 chunk 的 is_latest 标记为 false ---
        # [修复] 原有 term 查询对字段映射方式敏感，零更新时无日志无报错，难以排查
        # 改为：① 使用 bool should 同时匹配 metadata.source.keyword 和 metadata.source
        #       ② 加 refresh=True 保证 update 对后续 bulk 写入可见
        #       ③ 打印 updated/failures 数量，便于排查
        try:
            ubq_body = {
                "script": {
                    "source": "if (ctx._source.containsKey('metadata')) { ctx._source.metadata.is_latest = false } else { ctx._source.is_latest = false }",
                    "lang": "painless"
                },
                "query": {
                    "bool": {
                        "should": [
                            {"term": {"metadata.source.keyword": source_name}},
                            {"term": {"metadata.source": source_name}}
                        ],
                        "minimum_should_match": 1
                    }
                }
            }
            ubq_resp = self.es.update_by_query(
                index=INDEX_NAME, body=ubq_body,
                ignore_unavailable=True, conflicts="proceed", refresh=True
            )
            updated_count = ubq_resp.get("updated", 0)
            failures     = ubq_resp.get("failures", [])
            print(f"[{source_name}] 历史版本标记过期: updated={updated_count}, failures={len(failures)}")
            if updated_count == 0:
                print(f"  ⚠️ 0 条被更新，可能字段映射不匹配或索引内暂无同名文档（首次上传属正常）")
        except Exception as e:
            print(f"⚠️ 标记历史版本过期失败: {e}")


        # --- [双粒度写入] 接收 dict 返回值，分别处理 coarse 和 fine chunks ---
        chunk_sets = self.splitter.process_document(raw_text)
        coarse_chunks = chunk_sets.get('coarse', [])
        fine_chunks   = chunk_sets.get('fine', [])
        # 合并两路，附带粒度标签
        all_chunk_pairs = [(c, 'coarse') for c in coarse_chunks] + \
                          [(c, 'fine')   for c in fine_chunks]

        t2 = time.time()
        print(f"[{os.path.basename(file_path)}] 双粒度切片耗时: {t2 - t1:.3f} 秒 "
              f"(粗: {len(coarse_chunks)}, 细: {len(fine_chunks)})")

        # [A2] 计算本次入库的版本号（在循环外，所有 chunk 共享同一版本）
        new_version  = self._get_next_version(source_name)
        version_at   = int(time.time() * 1000)
        uploader_id  = ext_metadata.get("uploader_id", "system")
        visibility   = ext_metadata.get("visibility", "INTERNAL")
        dept_levels  = self._compute_dept_levels(ext_metadata.get("dept_code"))
        access_groups = ext_metadata.get("access_groups") or []
        # tags_kw：keyword 类型的标签列表（区别于原有 text 类型的 tags 字段）
        tags_kw = ext_metadata.get("tags") or []
        if isinstance(tags_kw, str):
            tags_kw = [t.strip() for t in tags_kw.split(",") if t.strip()]

        # [Phase 1 缺陷1修复] 读取 Java DocIngestService 预计算的 acl_tokens 字段
        # Java 通过 Redis 队列传递逗号分隔字符串，如 "_INTERNAL,USER:U001,DEPT:620102"
        # 若不存在（SFTP/批量导入等旁路场景），在 Python 侧兜底自行计算
        raw_acl_tokens = ext_metadata.get("aclTokens") or ext_metadata.get("acl_tokens") or ""
        if raw_acl_tokens and isinstance(raw_acl_tokens, str):
            # Java 已预计算：直接拆分还原为列表
            acl_tokens = [t.strip() for t in raw_acl_tokens.split(",") if t.strip()]
        else:
            # 旁路兜底：Python 侧根据 visibility/uploader/dept_code 自行计算
            # 保证 SFTP 批量导入 / 命令行脚本场景同样写入正确的 acl_tokens
            acl_tokens = self._compute_acl_tokens(visibility, dept_levels.get("dept_code_full"), uploader_id, source_name)

        print(f"[{source_name}] 版本 v{new_version} | visibility={visibility} | dept={dept_levels.get('dept_code_full')} | acl_tokens={acl_tokens}")

        # [A5] 预构建 section_path → coarse doc_id 映射表
        # 逐个 coarse chunk 计算其将要分配的 doc_id（与主循环逻辑一致）
        # key 选用 section_path，没有 section_path 时用 chunk 内容前 50 字作兼容键
        coarse_section_map: dict = {}
        ci_global = 0  # 全局索引计数器（与主循环的 enumerate(i) 对应）
        for chunk in coarse_chunks:
            if chunk.quality_score is None or chunk.quality_score >= 0.30:
                # [修复] 与主循环 doc_id 对齐，加入 v{new_version}，避免不同版本引用混淆
                coarse_doc_id = f"{file_base_hash}_v{new_version}_chunk_{ci_global}"
                key = chunk.section_path if chunk.section_path else chunk.content[:50]
                coarse_section_map[key] = coarse_doc_id
            ci_global += 1

        for i, (chunk, granularity) in enumerate(all_chunk_pairs):
            # [A2] 质量过滤：低于阈值的 chunk 直接跳过，不向量化也不入库
            # 常见低质量 chunk 类型：页眉/页脚、目录行、单字行、乱码片段
            if chunk.quality_score is not None and chunk.quality_score < 0.30:
                continue

            # 向量化推理（入库端不加 query instruction prefix，与检索端对称）
            vecs = model_manager.encode(chunk.content)
            vector = vecs[0] if vecs else [0.0] * 1024

            # [A5] 定义 chunk 的 doc_id（含版本号，保证不同版本 chunk 不互相覆盖）
            # 根因：原来 doc_id 只含 file_base_hash+index，同名文件重复上传时 v2 会直接覆盖 v1
            doc_id = f"{file_base_hash}_v{new_version}_chunk_{i}" if granularity == 'coarse' \
                     else f"{file_base_hash}_v{new_version}_fine_{i}"

            # [A5] fine chunk 通过 section_path 查找其父 coarse chunk
            # 没有 section_path 时用前 50 字内容作备用键匹配
            if granularity == 'fine':
                lookup_key = chunk.section_path if chunk.section_path else chunk.content[:50]
                parent_chunk_id = coarse_section_map.get(lookup_key)
            else:
                parent_chunk_id = None  # coarse 本身是根节点，无父

            # 3. 构造 ES 文档
            doc = {
                "_op_type": "index",
                "_index": INDEX_NAME,
                "_id": doc_id,
                "_source": {
                    "content":          chunk.content,
                    "vector":           vector,
                    "chunk_granularity": granularity,
                    "parent_chunk_id":  parent_chunk_id,  # [A5] coarse=None, fine=父chunk doc_id
                    "keywords":         keywords,
                    # [Phase 1 缺陷1修复] acl_tokens 必须写在根级（keyword 数组）
                    # 这是扁平化 ACL 架构的核心字段，Java 侧权限查询通过 terms 求交集
                    # 例：["_INTERNAL", "USER:U001", "DEPT:620102"]
                    "acl_tokens":       acl_tokens,
                    "metadata": {
                        "source":           source_name,
                        "chunk_id":         i,
                        "chunk_type":       chunk.chunk_type,
                        "section_path":     chunk.section_path,
                        "quality_score":    chunk.quality_score,
                        "is_latest":        True,
                        # --- 版本管理字段 (B1/B2) ---
                        "doc_version":      new_version,
                        "version_at":       version_at,
                        "updated_by":       uploader_id,
                        # --- 部门编码分级字段 (C1) ---
                        **dept_levels,
                        # --- 权限与标签字段 (C2/C6) ---
                        "visibility":       visibility,
                        "access_groups":    access_groups,
                        "uploader_id":      uploader_id,
                        "tags_kw":          tags_kw,
                        # --- 原有元数据字段 ---
                        "tags":             ext_metadata.get("tag"),
                        "publish_time":     ext_metadata.get("publish_time"),
                        "document_number":  ext_metadata.get("document_number"),
                        "dynamic_meta":     doc_meta,
                        "owner":            ext_metadata.get("owner"),
                        "search_queries":   ext_metadata.get("search_queries"),
                        "data_source":      ext_metadata.get("data_source", "document"),
                        # 保留 owner_dept_id 兼容老代码查询，但内容改为实际部门编码
                        "owner_dept_id":    dept_levels.get("dept_code_full") or "global",
                    }
                }
            }

            actions.append(doc)


        # ── 阶段2 [性能关键重构] 先 bulk 写入 ES，再后台异步回填 colloquial_vector
        # 根因：原代码在主流程里并发调 LLM，Ollama 内部串行推理，
        #   N chunk × 20~40s/call ≈ 3~7 分钟。文档上传体验极差。
        # 修复：先写 ES（立即可搜索），再由 daemon 线程逐个补填 colloquial_vector。

        # 4. 先批量写入 ES（无需等待 LLM）
        helpers.bulk(self.es, actions)
        t3 = time.time()
        total_chunks = len(all_chunk_pairs)
        print(f"[{os.path.basename(file_path)}] 向量化+写入 ES 耗时: {t3 - t2:.3f} 秒 (存入 {total_chunks} 个切片)")
        print(f"✅ [{os.path.basename(file_path)}] 处理完成 | 全链路总耗时: {t3 - t0:.3f} 秒")

        # ── 阶段3：后台 daemon 线程异步回填 colloquial_vector ─────────────────────
        # 只回填 fine 粒度 chunk（colloquial 语义更精细，coarse 块的口语化意义有限）
        _fine_for_colloq = [
            (doc["_id"], doc["_source"]["content"])
            for doc in actions
            if doc["_source"].get("chunk_granularity") == "fine"
        ]
        _ai_host_snap = self.ai_host
        _es_snap      = self.es
        _src_snap     = source_name

        def _async_fill_colloquial(fine_actions, ai_host, es, src_name):
            """
            业务功能：后台为 fine chunk 生成并回填 colloquial_vector。
            调用路径：LLM 生成短语 -> BGE-M3 向量化 -> ES update API 逐个更新。
            失败容忍：单个 chunk 失败不影响其他，也不影响主索引可用性。
            """
            import requests as _rq
            import numpy   as _np
            import json    as _json
            ok = skip = 0
            for doc_id, content in fine_actions:
                try:
                    r = _rq.post(
                        f"{ai_host}/api/ai/colloquial/generate",
                        json={"text": content},
                        timeout=60.0
                    )
                    if r.status_code == 200:
                        phrases = _json.loads(r.content.decode("utf-8")).get("data", [])
                        if phrases:
                            vecs = [model_manager.encode(p) for p in phrases]
                            vv   = [v[0] for v in vecs if v]
                            if vv:
                                c_vec = _np.mean(_np.array(vv), axis=0).tolist()
                                es.update(
                                    index=INDEX_NAME, id=doc_id,
                                    body={"doc": {"colloquial_vector": c_vec}},
                                    retry_on_conflict=3
                                )
                                ok += 1
                                continue
                    skip += 1
                except Exception as _ce:
                    skip += 1
                    print(f"  ⚠️ [AsyncColloquial] {doc_id[:20]}... 失败: {_ce}")
            print(f"  ✅ [AsyncColloquial] '{src_name}' 回填完成 ok={ok} skip={skip}")

        if _fine_for_colloq:
            _bg = threading.Thread(
                target=_async_fill_colloquial,
                args=(_fine_for_colloq, _ai_host_snap, _es_snap, _src_snap),
                daemon=True,
                name=f"colloq-{source_name[:16]}"
            )
            _bg.start()
            print(f"  [AsyncColloquial] 后台线程已启动，{len(_fine_for_colloq)} 个 fine chunk 将在后台回填")

        # 5. 异步生成 Q&A 预置索引（仅对 fine chunks）
        if fine_chunks:
            try:
                self._generate_and_index_qa_pairs(fine_chunks, source_name, file_base_hash)
            except Exception as qa_err:
                print(f"⚠️ [Q&A] 生成 Q&A 对失败（不影响文档主索引）: {qa_err}")

        # 6. 同步更新 kb_doc_meta 文档级向量（供相似文档搜索使用）
        try:
            data_src = ext_metadata.get("data_source", "document") if ext_metadata else "document"
            self._update_doc_meta(source_name, actions, data_src, content_hash=content_hash)
        except Exception as meta_err:
            print(f"⚠️ [DocMeta] 更新 kb_doc_meta 失败（不影响主索引）: {meta_err}")

        # [C5] 权限事件回调：通知 Java 侧写入 doc_version_history 和 doc_permission_events
        # 采用 HTTP 调用方式，保持 Python/Java 解耦；失败不阻塞主流程
        try:
            import requests as _rq
            java_host = os.getenv("JAVA_SERVICE_HOST", "http://127.0.0.1:8080")
            internal_token = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
            perm_payload = {
                "sourceName":  source_name,
                "docVersion":  new_version,
                "contentHash": content_hash,
                "chunkCount":  len(actions),          # 实际写入 chunk 数
                "uploaderId":  uploader_id,
                "visibility":  visibility,
                "deptCode":    ext_metadata.get("dept_code")
            }
            resp = _rq.post(f"{java_host}/api/doc/perm/record",
                            json=perm_payload,
                            headers={"X-Internal-Token": internal_token},
                            timeout=5.0)
            if resp.status_code == 200:
                print(f"✅ [C5 PermEvent] '{source_name}' v{new_version} 权限事件已写入 PG")
            else:
                print(f"⚠️ [C5 PermEvent] Java 侧返回异常: {resp.status_code} {resp.text[:100]}")
        except Exception as perm_err:
            # 权限事件写入失败不影响 ES 主索引，仅记录警告
            print(f"⚠️ [C5 PermEvent] 权限事件写入失败（ES 入库不受影响）: {perm_err}")

        # [文档注册] 写入 kb_doc_registry — MySQL 侧文档目录权威来源
        # 与 C5 权限事件同一时机调用，失败同样不阻塞 ES 主索引
        try:
            import requests as _rq
            java_host = os.getenv("JAVA_SERVICE_HOST", "http://127.0.0.1:8080")
            internal_token = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
            registry_payload = {
                "sourceName":   source_name,
                "docVersion":   new_version,
                "docId":        f"{source_name}:{new_version}",
                "storagePath":  file_path,
                "targetIndex":  ext_metadata.get("targetIndex", "kb_document_v1") if ext_metadata else "kb_document_v1",
                "chunkCount":   len(actions),
                "contentHash":  content_hash,
                "docNumber":    ext_metadata.get("docNumber")    if ext_metadata else None,
                "unit":         ext_metadata.get("unit")         if ext_metadata else None,
                "tags":         ext_metadata.get("tag")          if ext_metadata else None,
                "publishTime":  ext_metadata.get("publishTime")  if ext_metadata else None,
                "visibility":   visibility,
                "deptCode":     ext_metadata.get("dept_code")    if ext_metadata else None,
                "uploaderId":   uploader_id,
                "uploaderName": ext_metadata.get("owner")        if ext_metadata else None,
            }
            resp = _rq.post(f"{java_host}/api/v1/internal/doc/registry",
                            json=registry_payload,
                            headers={"X-Internal-Token": internal_token},
                            timeout=5.0)
            if resp.status_code == 200:
                print(f"✅ [DocRegistry] '{source_name}' v{new_version} 已写入 kb_doc_registry")
            else:
                print(f"⚠️ [DocRegistry] Java 侧返回异常: {resp.status_code} {resp.text[:100]}")
        except Exception as reg_err:
            print(f"⚠️ [DocRegistry] registry 写入失败（ES 入库不受影响）: {reg_err}")


        return {
            "parseDurationMs": int((t1 - t0) * 1000),
            "chunkDurationMs": int((t3 - t1) * 1000),
            "chunkCount": total_chunks,
            "coarseCount": len(coarse_chunks),
            "fineCount": len(fine_chunks),
            "parseResult": parse_result
        }


    def _get_next_version(self, source_name: str) -> int:
        """
        业务功能：向 Java 服务查询文档的下一个版本号，保证版本号单调递增且无并发竞争。
        关键流程：GET /api/v1/internal/doc/next-version?sourceName=xxx
                  → Java 事务锁保证原子性 → 返回 max(doc_version)+1
        降级策略：网络异常或接口返回非200时，回退到从 ES kb_doc_registry 自行推断（可能有竞态）
        """
        import requests as _rq
        try:
            java_host = os.getenv("JAVA_SERVICE_HOST", "http://127.0.0.1:8080")
            internal_token = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
            resp = _rq.get(
                f"{java_host}/api/v1/internal/doc/next-version",
                params={"sourceName": source_name},
                headers={"X-Internal-Token": internal_token},
                timeout=5.0
            )
            if resp.status_code == 200:
                data = resp.json().get("data", 1)
                version = int(data) if data else 1
                print(f"[{source_name}] 从 Java 取得版本号: v{version}")
                return version
            else:
                print(f"⚠️ [版本号] Java 接口返回 {resp.status_code}，降级使用 v1")
                return 1
        except Exception as e:
            print(f"⚠️ [版本号] 无法从 Java 取得版本号（{e}），降级使用 v1")
            return 1

    def _update_doc_meta(self, source_name: str, chunk_actions: list, data_source: str = "document", content_hash: str = None):

        """
        业务功能：在文档入库完成后，将所有 fine chunk 向量均值池化为 doc_vector，
                   并写入 kb_doc_meta 索引，供「相似文档搜索」功能使用。
        核心原理：doc_vector = mean(all fine chunk vectors) → L2 normalize
        幺等：以 source_name 为 _id，重复上传只会改写而不是新增。
        """
        import numpy as _np
        import urllib.parse

        # 收集所有 fine chunk 的已计算向量（得益于 chunk_actions 已有 vector 字段）
        fine_vectors = [
            action["_source"]["vector"]
            for action in chunk_actions
            if action.get("_source", {}).get("chunk_granularity") == "fine"
               and action.get("_source", {}).get("vector")
        ]

        if not fine_vectors:
            print(f"  [DocMeta] '{source_name}' 无 fine chunk 向量，跳过 kb_doc_meta 更新")
            return

        # 均值池化 + L2 归一化
        arr = _np.array(fine_vectors, dtype=_np.float32)
        mean_vec = _np.mean(arr, axis=0)
        norm = _np.linalg.norm(mean_vec)
        if norm > 0:
            mean_vec = mean_vec / norm

        META_INDEX = "kb_doc_meta"
        doc_id = urllib.parse.quote(source_name, safe="")
        body = {
            "source":       source_name,
            "source_name":  source_name,     # keyword 副本，供 .keyword 精确查询
            "data_source":  data_source,
            "chunk_count":  len(fine_vectors),
            "doc_vector":   mean_vec.tolist(),
            "updated_at":   int(time.time() * 1000),
            # [A4] 内容哈希：写入 kb_doc_meta，供下次上传时做去重比对
            "content_hash": content_hash,
            # 版本及权限摘要（冗余存储，加速管理端查询）
            "is_latest":    True,
        }
        self.es.index(index=META_INDEX, id=doc_id, body=body)
        print(f"✅ [DocMeta] kb_doc_meta 已同步: '{source_name}' ({len(fine_vectors)} fine chunks → doc_vector)")


    def _generate_and_index_qa_pairs(self, fine_chunks, source_name: str, file_base_hash: str):
        """
        业务功能：离线安全限如悲履直投 fine chunk 调用 LLM 生成问题，写入 kb_qa_pairs 索引。
        关键流程：逐个 fine chunk → LLM 生成 3-5 个问题 → BGE-M3 向量化每个问题 → 批量写入 ES
        圆阴设计：局部失败不要抾尽主流索引。媂果 LLM 调用失败则跳过该条文。
        """
        import requests
        # 只处理内容长度在 15 字以上的有意义 chunk，最多 25 个，避免过多串行 LLM 调用
        meaningful_chunks = [c for c in fine_chunks if len(c.content.strip()) >= 15][:25]
        print(f"💬 [Q&A] 开始为 {len(meaningful_chunks)}/{len(fine_chunks)} 个 fine chunks 生成 Q&A 对...")
        qa_actions = []

        for idx, chunk in enumerate(meaningful_chunks):
            try:
                # Step 1: 调用 AI Service 生成问题
                resp = requests.post(
                    f"{self.ai_host}/api/ai/qa/generate",
                    json={"text": chunk.content},
                    timeout=55.0  # 必须大于 AI Service 内部 LLM 调用超时(45s)
                )
                if resp.status_code != 200:
                    continue
                questions = resp.json().get("data", [])
                if not questions:
                    continue

                # Step 2: 对每个问题向量化并写入
                for q_idx, question in enumerate(questions):
                    vecs = model_manager.encode(question)
                    if not vecs:
                        continue
                    q_vector = vecs[0]
                    qa_id = f"{file_base_hash}_qa_{idx}_{q_idx}"
                    qa_actions.append({
                        "_op_type": "index",
                        "_index": QA_INDEX_NAME,
                        "_id": qa_id,
                        "_source": {
                            "question":        question,
                            "question_vector": q_vector,
                            "answer_content":  chunk.content,
                            "answer_chunk_id": f"{file_base_hash}_fine_{idx}",
                            "section_path":    chunk.section_path or "",
                            "source":          source_name,
                            "is_latest":       True
                        }
                    })
            except Exception as e:
                print(f"  ⚠️ [Q&A] chunk {idx} 处理失败: {e}")
                continue

        if qa_actions:
            helpers.bulk(self.es, qa_actions)
            print(f"✅ [Q&A] 共写入 {len(qa_actions)} 条 Q&A 对到 {QA_INDEX_NAME}")
        else:
            print(f"  [Q&A] 无有效问题生成，跳过 Q&A 索引")

    def search(self, query, top_k=5):
        """
        业务功能：本地调试用语义搜索。
        修复：原代码使用不存在的 self.model（AttributeError），改为 model_manager.encode。
        """
        query_input = query if len(query.strip()) <= 15 else \
            "Represent this sentence for searching relevant passages: " + query
        vecs = model_manager.encode(query_input)
        if not vecs:
            return []
        query_vector = vecs[0]
        res = self.es.search(index=INDEX_NAME, body={
            "knn": {
                "field": "vector",
                "query_vector": query_vector,
                "k": top_k,
                "num_candidates": 100
            },
            "_source": ["content", "metadata"]
        })
        return res['hits']['hits']
        
        search_query = {
            "knn": {
                "field": "vector",
                "query_vector": query_vector,
                "k": top_k,
                "num_candidates": 100
            },
            "_source": ["content", "metadata"]
        }
        
        res = self.es.search(index=INDEX_NAME, body=search_query)
        return res['hits']['hits']

    def _process_single(self, file_path):
        """供线程池调用的单文件处理包装函数"""
        try:
            self.process_and_index(file_path)
            return {"file": os.path.basename(file_path), "status": "success"}
        except Exception as e:
            return {"file": os.path.basename(file_path), "status": "error", "message": str(e)}

    def process_directory(self, directory_path, max_workers=4):
        """异步批量处理目录下所有支持的文档"""
        print(f"🔍 正在扫描目录: {directory_path}")
        supported_exts = ['.docx', '.pdf', '.doc', '.xlsx', '.pptx', '.txt', '.md']
        
        files_to_process = []
        for root, _, files in os.walk(directory_path):
            for file in files:
                if os.path.splitext(file)[1].lower() in supported_exts:
                    files_to_process.append(os.path.join(root, file))

        total_files = len(files_to_process)
        print(f"📂 发现 {total_files} 个待处理文档。正在使用 {max_workers} 个线程进行并发处理...")
        
        results = []
        # 使用 ThreadPoolExecutor 实现并发处理
        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            # 提交所有任务
            future_to_file = {executor.submit(self._process_single, f): f for f in files_to_process}
            
            # 使用 tqdm 显示进度条
            for future in tqdm(concurrent.futures.as_completed(future_to_file), total=total_files, desc="向量化入库进度"):
                results.append(future.result())
                
        # 统计结果
        success_count = sum(1 for r in results if r["status"] == "success")
        error_count = total_files - success_count
        print(f"\n✅ 批量导入完成！")
        print(f"📊 统计报告: 总数 {total_files} | 成功 {success_count} | 失败 {error_count}")
        
        if error_count > 0:
            print("❌ 以下文件处理失败:")
            for r in [res for res in results if res["status"] == "error"]:
                print(f"  - {r['file']}: {r['message']}")

if __name__ == "__main__":
    # 使用案例 (自动化处理整个 mock 目录)
    pipeline = RAGPipeline()
    BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    mock_dir = os.path.join(BASE_DIR, "mock")
    
    if os.path.exists(mock_dir):
        pipeline.process_directory(mock_dir)
        
        # 验证检索 (仅当索引可能已创建时执行)
        if pipeline.es.indices.exists(index=INDEX_NAME):
            print("\n" + "="*30)
            print("🚀 正在验证批量入库后的检索效果...")
            test_query = "地方政府债务余额"
            results = pipeline.search(test_query, top_k=3)
            print(f"查询词: '{test_query}'")
            print("--- 语义检索结果 ---")
            for hit in results:
                print(f"Score: {hit['_score']:.4f}, Source: {hit['_source']['metadata']['source']}")
                print(f"Content: {hit['_source']['content'][:100]}...\n")
        else:
            print(f"⚠️ 警告: 索引 {INDEX_NAME} 未创建，可能是因为没有任何文档被成功入库。")
    else:
        print(f"❌ 找不到 mock 目录: {mock_dir}")
