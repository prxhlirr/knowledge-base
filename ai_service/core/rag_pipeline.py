import os
import time
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
from core.cleaning.text_cleaner import TextCleaner               # [架构重构阶段一] 文本净化
from core.indexing.es_setup import ESSetup, INDEX_NAME, QA_INDEX_NAME, DOC_META_INDEX  # [架构重构阶段一] ES初始化 + 常量
from core.indexing.dedup_checker import ContentDedupChecker       # [架构重构阶段四] 内容指纹去重
from core.indexing.doc_indexer import DocIndexer                  # [架构重构阶段四] bulk写入+版本管理+元数据更新
from core.parsing.parser_factory import ParserFactory             # [架构重构阶段二] 文档格式解析工厂

# [D1 根治] 删除全局串行锁：改为每次调用注入独立 UserInstallation 目录。
# 原 _doc_com_lock 串行化所有 LO 调用，批量导入时阻塞队列积压。
# 根治方案：每个 soffice 子进程使用独立 --env:UserInstallation=file:///tmp/lo_user_XXXX，
# 从根本上消除多实例共享用户目录的冲突，无需互斥锁。

# 配置参数（P1-6 修复：完全环境变量注入，解除本机路径绑定）
MODEL_PATH  = os.getenv("MODEL_PATH",  "/app/models/onnx_native/bge-m3")
ES_HOST     = os.getenv("ES_HOST",     "http://elasticsearch:9200")
ES_USER     = os.getenv("ES_USER",     "")
ES_PASS     = os.getenv("ES_PASS",     "")
# [架构重构阶段一] 以下常量已迁移至 core/indexing/es_setup.py，上方 import 负责引入
# INDEX_NAME / QA_INDEX_NAME / DOC_META_INDEX → 见 es_setup.py

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
        # [架构重构阶段一] 索引初始化委托给 ESSetup，替代原4个独立调用
        ESSetup(self.es).setup()
        self.splitter = SemanticChunker(model_manager.cfg if hasattr(model_manager, 'cfg') else {})
        # [架构重构阶段一] 文本净化委托给 TextCleaner，替代原 _clean_raw_text 静态方法
        self.cleaner = TextCleaner()
        self.md_converter = MarkItDown()
        # [架构重构阶段二] 文档解析委托给 ParserFactory，替代原 extract_text 超级方法
        self.parser_factory = ParserFactory(self.md_converter)
        # [架构重构阶段四] 内容去重和写入委托给独立组件
        self.dedup_checker = ContentDedupChecker(self.es)
        self.doc_indexer   = DocIndexer(self.es)
        self.ai_host = os.getenv("AI_SERVICE_HOST", "http://localhost:8001")  # AI 本身，供自身调用
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
                        "display_content":   {"type": "text", "index": False},  # [P0-5A] 含面包屑的展示内容，不建倒排索引
                        "chunk_granularity": {"type": "keyword"},
                        "parent_chunk_id":   {"type": "keyword"},
                        "sparse_vector":     {"type": "rank_features"},
                        "colloquial_vector": {
                            "type": "dense_vector",
                            "dims": 1024,
                            "index": False  # [Fix] 无检索逻辑使用，index:false 消除冗余 HNSW
                        },
                        "metadata": {
                            "properties": {
                                "document_number": {"type": "keyword"},
                                "section_path":    {"type": "keyword"},
                                "chunk_type":      {"type": "keyword"},
                                "quality_score":   {"type": "float"},
                                "dynamic_meta":    {"type": "object", "dynamic": False},  # [Fix] 防字段爆炸
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
                                "doc_type":       {"type": "keyword"},  # [Task7] 文档类型（法规/通知/报告等）
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
                                "colloquial_vector": {"type": "dense_vector", "dims": 1024, "index": False},  # [Fix]
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
                                        "dynamic_meta": {"type": "object", "dynamic": False},  # [Fix] 防字段爆炸
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
            # [功能热修复] 系统配置流尚未打通时的兜底防线：中国公文文号高容错提取
            # 兼容变体：
            # 1. 机构带括号：xx（政治）发、xx(党)函
            # 2. 非标准尾缀：xxx报、xx经侦
            # 3. 年份错乱打字：[2024]、(2020)、<1999>
            # 4. 编号修饰：[2024]第05号
            rules = [
                {
                    "key": "docNumber",
                    "regex": r"([A-Za-z\u4e00-\u9fa5]+(?:[（(][A-Za-z\u4e00-\u9fa5]+[)）])?[A-Za-z\u4e00-\u9fa5]{0,10}?\s*[〔\[(（【<]\s*[12]\d{3}\s*[〕\])）】>]\s*(?:第)?\s*\d{1,6}\s*号)"
                }
            ]
            
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
        规则5 - 行内重复字符：任意单字符连续出现 ≥8 次 → 盖章区装饰噪声，删除该行
                覆盖场景：antiword 将印章边框/环形水印解析为"缓缓缓缓缓缓缓缓..."
        规则6 - Symbol/WingDings 字体乱码行：拉丁扩展区(U+0100-U+03FF)字符密度 >25%
                且汉字 <3 个 → 红头标题/落款区字体错位产生的乱码，删除该行
                覆盖场景：antiword 将五角星/装饰边框转为 ψ Υ ħ Ā Ĵ 等希腊/IPA 字符
        规则7 - 低有效字符密度行：有效字符(汉字+英文+数字+常用标点)占比 <20% → 纯噪声
                覆盖场景："è á Ë ② ZĀ Ā Ā Ā Ë ② ħĀ" 这类混合乱码行
        规则8 - 内容门控：净化后有效汉字/字母 < 50 字符 → 返回空串拒绝入库
        """
        if not text:
            return text

        import re as _re
        lines = text.splitlines()
        cleaned: list = []
        prev_line: str = None
        repeat_count: int = 0

        _watermark_re     = _re.compile(r'([\u4e00-\u9fa5])\1{3,}')
        _table_sep_re     = _re.compile(r'^\s*\|[\s\-\|]+\|\s*$')
        _inline_repeat_re = _re.compile(r'(.)\1{7,}')   # 规则5：任意字符连续>=8次
        # [Bug-9 修复] 将规则8正则提升至循环外，避免每行都重新编译，O(N)→O(1)
        # 根因：re.compile 是 CPU 密集操作，原代码在 for line in lines 循环体内每行都编译一次
        _normal_chars_re  = _re.compile(
            r'[\u4e00-\u9fa5\x20-\x7E\u3000-\u303F\uFF00-\uFFEF'
            r'\u2160-\u217F\u2460-\u24FF\u2500-\u257F\u2190-\u21FF'
            r'\u25A0-\u25FF\u2200-\u22FF\s]'
        )

        for line in lines:
            stripped = line.strip()
            if not stripped:
                prev_line = stripped
                repeat_count = 0
                continue

            # 规则1：水印行（单汉字连续出现>=4次）
            if _watermark_re.search(stripped):
                continue

            # 规则2：水印 Markdown 表格行（所有单元格均为同一汉字）
            if stripped.startswith('|') and stripped.endswith('|'):
                if _table_sep_re.match(stripped):
                    continue
                cells = [c.strip() for c in stripped.split('|') if c.strip() and c.strip() != '---']
                if cells and all(len(c) == 1 and '\u4e00' <= c <= '\u9fa5' for c in cells):
                    if len(set(cells)) == 1:
                        continue

            # 规则3：高频重复行折叠（连续>=3行相同 -> 折叠为1行）
            if stripped == prev_line:
                repeat_count += 1
                if repeat_count >= 2:
                    continue
            else:
                repeat_count = 0

            # 规则4：乱码行（U+FFFD 替换字符占比 >30%）
            replacement_count = stripped.count('\ufffd')
            if len(stripped) > 5 and replacement_count / len(stripped) > 0.30:
                continue

            # 规则5：行内单字符连续重复 >=8 次（盖章区/印章边框噪声）
            if _inline_repeat_re.search(stripped):
                continue

            # [优化] 废弃原规则6（拉丁扩展密度）和规则7（有效字符密度）：
            # 这两条专门针对 antiword 产物，在 Gotenberg/LO 路径下会误杀正常内容
            # [Bug-11 重大修复] 规则8 原设计存在致命白名单遗漏：
            # 漏防了通用标点区 (\u2000-\u206F)，导致双引号 “” 破折号 —— 等被判定为“外星乱码”。
            # 由于当前已由 MarkItDown 和 Docx 正规结构化兜底解析，非乱码源占主导，直接弃用此针对性的危险规则！
            # weird_chars = _normal_chars_re.sub('', stripped)
            # if len(weird_chars) >= 3 and len(weird_chars) / len(stripped) > 0.10:
            #     continue

            prev_line = stripped
            cleaned.append(line)

        result = '\n'.join(cleaned)

        # 内容门控：过滤空文件和纯噪声文档
        # [Bug-10+12 修复] 直接击穿底线降至 10 字：
        # 坚决不阻挡正常的单行简讯或 QA 联调生成的 20 字极短 `测试.docx`。
        effective_chars = _re.sub(r'[^\u4e00-\u9fa5a-zA-Z0-9]', '', result)
        if len(effective_chars) < 10:
            return ''

        return result

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

            # ─── 步骤2-HTML：.html/.htm 格式解析 ─────────────────────────────────────
            # 根因：政府网站公告/政策解读以 HTML 形式发布，但当前管线无 HTML 解析能力。
            # 方案：BeautifulSoup 剔除导航/页脚等噪声标签后提取正文，
            #       将 <h1/2/3> 直接转为 # ## ### Markdown 标题前缀，
            #       与 P路 docx 标题路径完全等价，semantic_chunker 可直接接管。
            if local_path.lower().endswith((".html", ".htm")) and not local_path.startswith("http"):
                try:
                    import chardet as _chardet
                    from bs4 import BeautifulSoup as _BS
                    with open(local_path, "rb") as _hf:
                        _hraw = _hf.read()
                    _henc = _chardet.detect(_hraw[:2000]).get("encoding") or "utf-8"
                    _soup = _BS(_hraw.decode(_henc, errors="replace"), "html.parser")
                    # 剔除政府网站 HTML 中常见的语义噪声标签（导航/页脚/脚本/样式）
                    for _noise in _soup(["nav", "footer", "header", "aside", "script", "style"]):
                        _noise.decompose()
                    _hlines = []
                    for _el in _soup.find_all(["h1", "h2", "h3", "h4", "p", "li", "td", "th"]):
                        _htxt = _el.get_text(strip=True)
                        if not _htxt:
                            continue
                        if _el.name == "h1":   _hlines.append(f"# {_htxt}")
                        elif _el.name == "h2": _hlines.append(f"## {_htxt}")
                        elif _el.name == "h3": _hlines.append(f"### {_htxt}")
                        elif _el.name == "h4": _hlines.append(f"#### {_htxt}")
                        else:                  _hlines.append(_htxt)
                    _html_result = "\n".join(_hlines).strip()
                    if _html_result:
                        print(f"✅ [extract_text] HTML 解析成功 ({len(_html_result)} 字符, enc={_henc})")
                        return _html_result
                except Exception as _html_err:
                    print(f"⚠️ [extract_text] HTML 解析失败，回退 MarkItDown: {_html_err}")

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
                    elif b"<html" in _magic.lower() or b"<!doctype" in _magic.lower():  # [Bug-7 修复] <!doc→<!doctype
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

                # ── OLE/unknown 路径（G路首选 → A/B/C路降级兜底）
                if _doc_format in ("ole", "unknown"):
                    # ── 🏆 G路（首选）：调用 Gotenberg 将 OLE/doc 转为 .docx，再用 python-docx 结构化提取
                    # [Bug-5 根治] 原 G路输出 PDF 再用 pypdf 提取，彻底丢失标题层次（Heading 1/2/3）。
                    # 修复策略：
                    #   1. 通过 Gotenberg-Output-Filename: output.docx 请求 LO 输出 .docx 格式
                    #   2. 检测响应魔数：PK 头(ZIP) → 正常 docx，以 python-docx 结构化解析
                    #   3. PDF 头 → Gotenberg 版本不支持 docx 输出，自动降级为 pypdf 提取（无结构）
                    # 两种情况均有完整 fallback，运行期不需要提前确认 Gotenberg 版本。
                    _p_text = ""
                    try:
                        import requests as _rq
                        _gotenberg_url = os.getenv("GOTENBERG_URL", "http://gotenberg:3000")
                        print(f"🔄 [G路] Gotenberg 转换（请求 docx 格式）: {os.path.basename(local_path)}")
                        with open(local_path, "rb") as _f:
                            _resp = _rq.post(
                                f"{_gotenberg_url}/forms/libreoffice/convert",
                                files={"files": (os.path.basename(local_path), _f)},
                                headers={"Gotenberg-Output-Filename": "output.docx"},
                                timeout=90
                            )

                        if _resp.status_code == 200:
                            _resp_bytes = _resp.content
                            _is_docx = _resp_bytes[:2] == b'PK'   # ZIP/OOXML 魔数
                            _is_pdf  = _resp_bytes[:4] == b'%PDF'

                            if _is_docx:
                                # ── G路-优质分支：Gotenberg 返回了真正的 .docx ──
                                import tempfile as _tmp
                                import docx as _docx_mod
                                with _tmp.NamedTemporaryFile(suffix=".docx", delete=False) as _t:
                                    _t.write(_resp_bytes)
                                    _tmp_docx_path = _t.name
                                try:
                                    _g_doc = _docx_mod.Document(_tmp_docx_path)
                                    _g_lines = []
                                    # [BUG-04+07 根治] 按 XML body 顺序遍历（保留表格原始位置）+ 合并单元格去重
                                    # 根因1(BUG-04)：for _para + for _tbl 两次循环将表格全部追加末尾
                                    # 根因2(BUG-07)：row.cells 对合并区域每个逻辑格均返回主格内容，产生重复列
                                    from docx.text.paragraph import Paragraph as _GxPara
                                    from docx.table import Table as _GxTable
                                    def _g_unique_cells(_row):
                                        _seen, _cells = set(), []
                                        for _c in _row.cells:
                                            _cid = id(_c._tc)
                                            if _cid not in _seen:
                                                _seen.add(_cid)
                                                _cells.append(_c.text.strip().replace('\n', ' '))
                                        return _cells
                                    for _ch in _g_doc.element.body:
                                        _lt = _ch.tag.split('}')[-1] if '}' in _ch.tag else _ch.tag
                                        if _lt == 'p':
                                            _para = _GxPara(_ch, _g_doc)
                                            _ptxt = _para.text.strip()
                                            if not _ptxt: continue
                                            _sname = (_para.style.name if _para.style else '').lower()
                                            if 'heading 1' in _sname or '标题 1' in _sname: _g_lines.append(f'# {_ptxt}')
                                            elif 'heading 2' in _sname or '标题 2' in _sname: _g_lines.append(f'## {_ptxt}')
                                            elif 'heading 3' in _sname or '标题 3' in _sname: _g_lines.append(f'### {_ptxt}')
                                            elif 'heading 4' in _sname or '标题 4' in _sname: _g_lines.append(f'#### {_ptxt}')
                                            else: _g_lines.append(_ptxt)
                                        elif _lt == 'tbl':
                                            _tbl = _GxTable(_ch, _g_doc)
                                            if not _tbl.rows: continue
                                            _g_lines.append('')
                                            _th = _g_unique_cells(_tbl.rows[0])
                                            if _th:
                                                _g_lines.append('| ' + ' | '.join(_th) + ' |')
                                                _g_lines.append('| ' + ' | '.join(['---']*len(_th)) + ' |')
                                            for _tr in _tbl.rows[1:]:
                                                _tc = _g_unique_cells(_tr)
                                                if any(_tc): _g_lines.append('| ' + ' | '.join(_tc) + ' |')
                                            _g_lines.append('')
                                    _p_text = '\n'.join(_g_lines).strip()
                                    if _p_text:
                                        _tbl_n = len(_g_doc.tables)
                                        print(f"✅ [G路-优] Gotenberg→docx→python-docx 成功 "
                                              f"({len(_p_text)} 字符, {_tbl_n} 个表格, 位置已保留)")
                                finally:
                                    os.remove(_tmp_docx_path)

                            elif _is_pdf:
                                # ── G路-降级分支：Gotenberg 版本不支持 docx 输出，回退 PDF 提取 ──
                                import io
                                from pypdf import PdfReader
                                print(f"⚠️ [G路-降] Gotenberg 返回 PDF（不支持 docx 输出），改用 pypdf 提取")
                                _reader = PdfReader(io.BytesIO(_resp_bytes))
                                _lines = []
                                for _pg in _reader.pages:
                                    _t = _pg.extract_text()
                                    if _t:
                                        _lines.append(_t.strip())
                                _p_text = '\n'.join(_lines)
                                _eff = len(_re.findall(r'[\u4e00-\u9fa5a-zA-Z0-9]', _p_text))
                                _density = _eff / max(len(_p_text), 1)
                                if _density < 0.10:
                                    print(f"⚠️ [G路-降] PDF 有效密度极低 {_density:.0%}，放弃")
                                    _p_text = ""
                                else:
                                    print(f"  [G路-降] pypdf 提取: {len(_p_text)} 字符 "
                                          f"(有效率={_density:.0%}, 注：无标题层次结构)")
                            else:
                                print(f"⚠️ [G路] 响应格式未知（魔数: {_resp_bytes[:4].hex()}），放弃")
                        else:
                            print(f"⚠️ [G路] Gotenberg HTTP {_resp.status_code}: {_resp.text[:100]}")

                    except Exception as _g_err:
                        _gotenberg_url = os.getenv("GOTENBERG_URL", "http://gotenberg:3000")
                        print(f"⚠️ [G路] 微服务通信异常 ({_gotenberg_url}): {_g_err}")

                    if _p_text.strip():
                        return _p_text.strip()

                    # ── 🥈 A路（降级）：antiword 固定 UTF-8 输出 + 收紧爆炸门控(×2) ─────
                    # 根因修复1：-m UTF-8.txt 强制 antiword 以 UTF-8 字符映射表输出，
                    #   消除 chardet 对图形控制字节的误判（GBK/Latin-1 伪判定）。
                    # 根因修复2：爆炸比例门控收紧至×2（正常文字提取不超过文件体积2倍）
                    try:
                        import subprocess
                        _aw_result = subprocess.run(
                            ["antiword", "-m", "UTF-8.txt", local_path],
                            capture_output=True, timeout=30
                        )
                        if _aw_result.returncode == 0 and _aw_result.stdout:
                            # 固定 UTF-8 解码，不再用 chardet 猜测混合字节流
                            _aw_text = _aw_result.stdout.decode("utf-8", errors="replace")

                            # [门控] 爆炸比例校验（收紧至×2）
                            _file_size = os.path.getsize(local_path)
                            if len(_aw_text) > _file_size * 2:
                                print(f"⚠️ [extract_text] antiword 输出膨胀异常 "
                                      f"({len(_aw_text)} chars vs {_file_size} bytes × 2)，"
                                      f"判定图形层污染，放弃 A 路 → 降级 B/C 路")
                                _aw_text = ""

                            # [有效密度二次验证]
                            if _aw_text.strip():
                                _eff_cnt = len(_re.findall(r'[\u4e00-\u9fa5a-zA-Z0-9]', _aw_text))
                                if _eff_cnt / max(len(_aw_text), 1) < 0.20:
                                    print(f"⚠️ [extract_text] antiword 有效字符密度不足，降级")
                                    _aw_text = ""

                            if _aw_text.strip():
                                # 按行过滤：保留含有效汉字/英文/数字的行，丢弃纯噪声行
                                _aw_lines = [
                                    _line.rstrip()
                                    for _line in _aw_text.splitlines()
                                    if _re.search(r'[\u4e00-\u9fa5a-zA-Z0-9]', _line)
                                ]
                                _aw_text = '\n'.join(_aw_lines)

                            if _aw_text.strip():
                                print(f"[extract_text] antiword 成功 ({len(_aw_text)} 字符)")
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

                    # C路：olefile WordDocument 流（UTF-16LE 全流解码，修正 WPS OLE 格式兼容问题）
                    # [Bug2修复] 原代码使用固定偏移 raw[0x80:] 作为正文起始位，
                    # 0x80=128 字节不是 Word OLE 格式的固定正文偏移，WPS 生成的 .doc
                    # 在此偏移处通常是 FIB（文件信息块）结构区而非正文字符流，
                    # 导致解码出乱码或无效字符而非实际文本内容。
                    # 修复：对整个 WordDocument 流做全量 UTF-16LE 解码，
                    # 用正则扫描出所有连续可读 Unicode 字符段（有效字符密度校验双重过滤），
                    # 不依赖任何固定偏移，对 MS Word 和 WPS 格式均有效。
                    try:
                        import olefile as _ole
                        import struct as _struct
                        if _ole.isOleFile(local_path):
                            ole = _ole.OleFileIO(local_path)
                            if ole.exists("WordDocument"):
                                raw = ole.openstream("WordDocument").read()
                                # 全流 UTF-16LE 解码（不使用 0x80 固定偏移）
                                text_raw = raw.decode("utf-16-le", errors="replace")
                                # 过滤：只保留汉字/字母/数字/常用标点，连续3个空白折叠为换行
                                readable = _re.sub(r'[^\u4e00-\u9fa5\w\s，。！？、；：《》（）【】]', " ", text_raw)
                                readable = _re.sub(r'\s{3,}', "\n", readable).strip()
                                ole.close()
                                # 有效字符密度验证（去除纯噪声的情况）
                                _eff_cnt = len(_re.findall(r'[\u4e00-\u9fa5a-zA-Z0-9]', readable))
                                _density = _eff_cnt / max(len(readable), 1)
                                if readable and len(readable) > 20 and _density >= 0.10:
                                    print(f"[extract_text] olefile 全流提取成功: {len(readable)} 字符, 有效率={_density:.0%}")
                                    return readable
                                elif readable:
                                    print(f"⚠️ [extract_text] olefile 全流解码有效率过低 ({_density:.0%})，内容可能为乱码")
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

            # ─── 步骤3-DOCX：.docx 文件优先用 python-docx 结构感知解析 ─────────────
            # 根因：MarkItDown 不识别 Word 原生 Heading 1/2/3 样式，所有段落被平铺为
            #       普通文本，导致 SemanticChunker 无法识别层次，切片质量大幅下降。
            # 方案：python-docx 通过 paragraph.style.name 精准识别标题级别，
            #       输出真正的 # ## ### Markdown 标题，与 HTML 解析路径完全等价。
            if local_path.lower().endswith(".docx") and not local_path.startswith("http"):
                try:
                    import docx as _docx_mod
                    _doc_obj = _docx_mod.Document(local_path)
                    _md_lines = []
                    # [BUG-04+07 根治] 按 XML body 顺序遍历（保留表格原始位置）+ 合并单元格去重
                    from docx.text.paragraph import Paragraph as _DxPara
                    from docx.table import Table as _DxTable
                    def _dx_unique_cells(_row):
                        """合并单元格去重：同一 _tc XML 节点只输出一次，消除重复列"""
                        _seen, _cells = set(), []
                        for _c in _row.cells:
                            _cid = id(_c._tc)
                            if _cid not in _seen:
                                _seen.add(_cid)
                                _cells.append(_c.text.strip().replace('\n', ' '))
                        return _cells
                    for _child in _doc_obj.element.body:
                        _ltag = _child.tag.split('}')[-1] if '}' in _child.tag else _child.tag
                        if _ltag == 'p':
                            _para = _DxPara(_child, _doc_obj)
                            _ptxt = _para.text.strip()
                            if not _ptxt:
                                continue
                            _sname = (_para.style.name if _para.style else '').lower()
                            if 'heading 1' in _sname or '标题 1' in _sname:
                                _md_lines.append(f'# {_ptxt}')
                            elif 'heading 2' in _sname or '标题 2' in _sname:
                                _md_lines.append(f'## {_ptxt}')
                            elif 'heading 3' in _sname or '标题 3' in _sname:
                                _md_lines.append(f'### {_ptxt}')
                            elif 'heading 4' in _sname or '标题 4' in _sname:
                                _md_lines.append(f'#### {_ptxt}')
                            else:
                                _md_lines.append(_ptxt)
                        elif _ltag == 'tbl':
                            _tbl = _DxTable(_child, _doc_obj)
                            if not _tbl.rows:
                                continue
                            _md_lines.append('')
                            _th = _dx_unique_cells(_tbl.rows[0])
                            if _th:
                                _md_lines.append('| ' + ' | '.join(_th) + ' |')
                                _md_lines.append('| ' + ' | '.join(['---'] * len(_th)) + ' |')
                            for _tr in _tbl.rows[1:]:
                                _tc = _dx_unique_cells(_tr)
                                if any(_tc):
                                    _md_lines.append('| ' + ' | '.join(_tc) + ' |')
                            _md_lines.append('')

                    _docx_result = '\n'.join(_md_lines).strip()
                    if _docx_result:
                        _tbl_cnt = len(_doc_obj.tables)
                        print(f'✅ [extract_text] python-docx 结构化解析 .docx 成功 ({len(_docx_result)} 字符, '
                              f'含 {_tbl_cnt} 个表格, 位置已保留)')
                        return _docx_result
                    print('⚠️ [extract_text] python-docx 提取内容为空，降级 MarkItDown')
                except Exception as _dx_err:
                    print(f'⚠️ [extract_text] python-docx 解析失败，降级 MarkItDown: {_dx_err}')

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
        raw_text = self.parser_factory.parse(file_path)  # [架构重构阶段二] 改用 ParserFactory

        # [解析错误早期拦截] extract_text 返回的错误提示不是正文，必须在进入清洗/入库链路前拦截
        # 根因：原代码仅在 _clean_raw_text 之后检查两种前缀（且没有 return 直接继续入库），
        #       导致 【.doc 格式解析失败】 等错误消息绕过拦截被当作正文内容索引进 ES。
        # 修复：统一检查所有【...】错误前缀（extract_text 的所有错误返回路径均以此开头），
        #       检测到则立即 return error，不进入后续清洗/向量化/入库流程。
        _PARSE_ERROR_PREFIXES = (
            "【内容提取受限】",
            "【系统脱机保护】",
            "【.doc 格式解析失败】",
            "【文件降级解析失败】",
        )
        if raw_text and any(raw_text.startswith(p) for p in _PARSE_ERROR_PREFIXES):
            _errmsg = raw_text[:200]
            print(f"⚠️ [{os.path.basename(file_path)}] 解析失败（错误消息已拦截，拒绝入库）: {_errmsg}")
            return {"status": "error", "reason": "parse_failed", "message": _errmsg}

        # [净化门控] 过滤水印/乱码/重复行，净化后内容<50字符则拒绝入库
        # 在清洗前记录原始字节数，用于打包环境追踪红头文件清洗效果
        _raw_bytes_before = len((raw_text or '').encode('utf-8', errors='ignore'))
        raw_text = self.cleaner.clean(raw_text or '')  # [架构重构阶段一]
        _raw_bytes_after = len((raw_text or '').encode('utf-8', errors='ignore'))
        _reduce_pct = (1 - _raw_bytes_after / _raw_bytes_before) * 100 if _raw_bytes_before > 0 else 0
        print(f"[{os.path.basename(file_path)}] 净化门控: {_raw_bytes_before} -> {_raw_bytes_after} 字节 "
              f"(净化率 {_reduce_pct:.1f}%)")
        if not raw_text:
            print(f"⚠️ [{os.path.basename(file_path)}] 净化后内容为空（水印/乱码），拒绝入库")
            return {"status": "skipped", "reason": "empty_after_cleaning"}

        parse_result = "OK"

        
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
        
        # 2. 双粒度切片（由下方 process_document 统一执行，此处不再冗余调用）
        # [Bug1修复] 原代码在此第一次调用 process_document，结果仅用于打日志后丢弃，
        # 但 SemanticChunker 内部的 prev_content（交叉重叠锚点）是有状态的，
        # 两次调用之间状态不同，导致实际入库的第二次调用产生不同分片数量。
        # 修复：删除第一次冗余调用，仅保留下方的双粒度调用，日志移至真正切片后打印。
        
        actions = []
        source_name = original_name if original_name else os.path.basename(file_path)
        ext_metadata = ext_metadata or {}

        # [标题检索修复] 提取文档标题，流程跳过切片循环外侧执行一次
        # doc_title 优先级： Java 传入 ext_metadata["title"] > Markdown H1 > 文件名去后缀
        doc_title = ext_metadata.get("title") or self._extract_doc_title(raw_text, source_name)

        # [架构重构阶段四] 内容指纹计算委托给 ContentDedupChecker（三级优先级：Java预计算 > 本地文件 > 文本兜底）
        content_hash, _hash_src = self.dedup_checker.compute_hash(file_path, raw_text, ext_metadata)
        print(f"[{source_name}] ContentHash: {content_hash[:12]}... (来源: {_hash_src})")

        force_reindex = ext_metadata.get("force_reindex", False)

        # [架构重构阶段四] 去重查询委托给 ContentDedupChecker
        _is_dup, _existing = self.dedup_checker.is_duplicate(
            source_name, content_hash, force_reindex
        )
        if _is_dup:
            print(f"⚠️ [A4 ContentDedup] '{source_name}' 与已有文档 '{_existing}' 内容指纹相同，跳过重复入库。")
            return {"status": "skipped", "reason": "duplicate_content", "existing": _existing}

        file_base_hash = hashlib.md5(source_name.encode('utf-8')).hexdigest()

        # --- [P2-2 修复] 删除第一次冗余的 process_document 调用 ---
        # 根因：原代码调用了两次 splitter.process_document(raw_text)，第一次结果未使用，
        #        第二次才分山粗细粒度；对大文档产生 2x 内存 + 2x 切片开销。
        # 此转振直接跳到双粒度切片，保留原有逻辑不变。

        # [P0-7B] 历史版本过期标记已移至 bulk 写入成功后执行（见下方）
        # 根因：原一先标记过期再写入，若写入失败，所有版本 is_latest=false，文档“消失”
        # 修复：先写入新版本成功后，再标记历史过期，保证任意时刻有 is_latest=true 的版本存在


        # --- [双粒度写入] 接收 dict 返回值，分别处理 coarse 和 fine chunks ---
        chunk_sets    = self.splitter.process_document(raw_text)
        coarse_chunks = chunk_sets.get('coarse', [])
        fine_chunks   = chunk_sets.get('fine', [])
        doc_type      = chunk_sets.get('doc_type', '通用')   # [Task7] 文档类型，写入 ES metadata 供分析
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
        visibility   = ext_metadata.get("visibility", "PUBLIC")
        acl_tokens_json = ext_metadata.get("acl_tokens_json", "")
        # [D2 V2 修复] 使用 JSON 数组反序列化替代逗号字符串分割
        # 根因：逗号分割无法处理 token 中包含逗号的边缘情况（如未来角色名包含逗号）
        # 降级策略：JSON 为空时降级为 _INTERNAL（比 _PUBLIC 更安全，防止数据泄露）
        if acl_tokens_json:
            try:
                import json as _json_lib
                acl_tokens = _json_lib.loads(acl_tokens_json)
                if not isinstance(acl_tokens, list) or len(acl_tokens) == 0:
                    raise ValueError("acl_tokens_json 解析结果为空列表")
            except Exception as _e:
                print(f"[WARN][ACL] acl_tokens_json 解析失败，降级为 _INTERNAL: {_e}")
                acl_tokens = ["_INTERNAL"]
        else:
            # Java 端未传 acl_tokens_json（兼容老版本部署过渡期）
            # 尝试兜底读取旧字段 acl_tokens（逗号字符串），最终降级为 _INTERNAL
            _legacy_tokens_str = ext_metadata.get("acl_tokens", "")
            if _legacy_tokens_str:
                acl_tokens = [t.strip() for t in _legacy_tokens_str.split(",") if t.strip()]
                print(f"[WARN][ACL] 使用遗留 acl_tokens 字段（逗号格式），建议升级 Java 端: {acl_tokens}")
            else:
                print(f"[WARN][ACL] acl_tokens_json 为空且无遗留字段，降级为 _INTERNAL，source={source_name}")
                acl_tokens = ["_INTERNAL"]
        dept_levels  = self._compute_dept_levels(ext_metadata.get("dept_code"))
        access_groups = ext_metadata.get("access_groups") or []
        # tags_kw：keyword 类型的标签列表（区别于原有 text 类型的 tags 字段）
        tags_kw = ext_metadata.get("tags") or []
        if isinstance(tags_kw, str):
            tags_kw = [t.strip() for t in tags_kw.split(",") if t.strip()]
        print(f"[{source_name}] 版本 v{new_version} | visibility={visibility} | dept={dept_levels.get('dept_code_full')}")

        # [Bug-13 修复 v2] coarse-fine 父子关联 —— 归一化空白后做内容子串匹配
        #
        # v1 修复的遗留问题：精确子串匹配仍然失效，原因是两个 Chunker 拼接多行时使用了不同的分隔符：
        #   CoarseChunker.push_body()  : "\n".join(current_body) ← 换行符拼接
        #   FineChunker.flush_bullet() : " ".join(current_bullet_lines) ← 空格拼接
        #   FineChunker._fallback_fine: ''.join(para_lines) + 切句 ← 不插分隔符
        # 导致：
        #   coarse.raw_content = "（一）经济总量持续扩大\n2024年，全县..."
        #   fine.raw_content   = "（一）经济总量持续扩大 2024年，全县..."  ← 空格不是\n
        #   第 50 字前缀 fine_key 含换行符差异，导致子串匹配一定失败，parent_chunk_id = null
        # 
        # 根治方案：构建索引时就对 coarse 内容做一次性空白归一化。
        #   归一化密级：\n / \t / \r / 多个空格 全部压缩为单个空格
        #   预处理时间：O(C) 共 len(coarse_content_index) 次，仅执行一次（不是每个 fine chunk 一次）
        #   匹配字数：50 字扩展为 80 字，少数个请求局部内容相同时提高局部匹配精度
        import re as _re
        coarse_content_index: list = []  # [(coarse_doc_id, normalized_text), ...]
        ci_global = 0
        for chunk in coarse_chunks:
            if chunk.quality_score is None or chunk.quality_score >= 0.30:
                coarse_doc_id = f"{file_base_hash}_v{new_version}_chunk_{ci_global}"
                c_text = chunk.raw_content or chunk.content
                # 预归一化：\n/\t/\r/多空格 → 单空格（仅此一行，后续所有 fine匹配免除重复归一化）
                c_norm = _re.sub(r'\s+', ' ', c_text).strip()
                coarse_content_index.append((coarse_doc_id, c_norm))
            ci_global += 1


        # [A2] 质量过滤：低于阈值的 chunk 不向量化也不入库
        valid_chunk_pairs = []
        for i, (chunk, granularity) in enumerate(all_chunk_pairs):
            if chunk.quality_score is not None and chunk.quality_score < 0.30:
                continue
            valid_chunk_pairs.append((i, chunk, granularity))

        # [P0-5A] 向量化使用裸文（raw_content），不含面包屑前缀
        texts_to_encode = [
            (chunk.raw_content if chunk.raw_content else chunk.content)
            for _, chunk, _ in valid_chunk_pairs
        ]

        # [性能优化] encode_dual：单次 ONNX 前向推理同时产出 dense + sparse 两路向量。
        # 根因：原代码两次独立调用 encode() + encode_sparse() = 两次完整 GPU 前向传播，
        #       而 BGE-M3 的 ONNX 图在一次 forward pass 中已同时输出
        #       sentence_embedding（dense）和 last_hidden_state（sparse 权重来源），
        #       两次推理完全是重复计算，浪费约 50% 推理时间。
        # 修复：encode_dual() 一次 model.run() 取走全部输出，节省一整次 GPU 推理。
        # 降级：encode_dual 内部任何异常会自动回退到两次独立调用，不影响入库正确性。
        # [Bug-15] 分批处理防止大文档 OOM，batch_size 默认 16（可通过 ENCODE_BATCH_SIZE 调整）

        _ENCODE_BATCH = int(os.getenv("ENCODE_BATCH_SIZE", "16"))
        batch_vectors: list = []
        batch_sparse_vectors: list = []
        if texts_to_encode:
            _total = len(texts_to_encode)
            print(f"[{source_name}] 开始分批双路向量化 {_total} 个文本块 (batch_size={_ENCODE_BATCH})...")
            t_vec_start = time.time()
            for _bi in range(0, _total, _ENCODE_BATCH):
                _batch = texts_to_encode[_bi: _bi + _ENCODE_BATCH]
                # 单次推理同时取 dense + sparse（替代原两次独立调用）
                _dvecs, _svecs = model_manager.encode_dual(_batch, top_k=64)
                batch_vectors.extend(_dvecs)
                batch_sparse_vectors.extend(_svecs)
            print(f"[{source_name}] 双路向量化完成，耗时: {time.time() - t_vec_start:.3f} 秒 "
                  f"(dense={len(batch_vectors)}, sparse={len(batch_sparse_vectors)})")



        for idx, (original_i, chunk, granularity) in enumerate(valid_chunk_pairs):
            # 获取批量计算的稠密向量结果
            vector = batch_vectors[idx] if idx < len(batch_vectors) else [0.0] * 1024
            # 获取稀疏向量（降级时为空字典，ES 该字段不写入）
            sparse_vector = batch_sparse_vectors[idx] if idx < len(batch_sparse_vectors) else {}

            # [A5] 定义 chunk 的 doc_id（含版本号，保证不同版本 chunk 不互相覆盖）
            doc_id = f"{file_base_hash}_v{new_version}_chunk_{original_i}" if granularity == 'coarse' \
                     else f"{file_base_hash}_v{new_version}_fine_{original_i}"

            # [Bug-13 v2] fine chunk 通过「归一化空白后的内容包含关系」建立父子关联
            if granularity == 'fine':
                _raw = (chunk.raw_content or chunk.content)[:80]
                # 归一化空白：FineChunker 用空格拼接多行，CoarseChunker 用\n，此处统一压缩后再匹配
                _fine_key = _re.sub(r'\s+', ' ', _raw).strip()
                parent_chunk_id = None
                if _fine_key:
                    for _cid, _cnorm in coarse_content_index:  # _cnorm 已预归一化
                        if _fine_key in _cnorm:
                            parent_chunk_id = _cid
                            break
            else:
                parent_chunk_id = None  # coarse 本身是根节点，无父

            # [P1-3A] chunk 级关键词：对当前 chunk 的裸文独立提取 Top5
            # 根因：文档级关键词导致不同章节的 chunk 共享同一关键词，使 BM25 分均假膈胀
            # 不加入 doc_meta 数据（文号/机构名）以避免未干迟内容匹配
            _raw_for_kw = chunk.raw_content if chunk.raw_content else chunk.content
            chunk_keywords = jieba.analyse.extract_tags(_raw_for_kw, topK=5)
            # doc_meta 提取的特殊元数据字段（文号等）操作性强则合并入关键词
            _meta_vals = [v for v in (doc_meta or {}).values() if v and v not in chunk_keywords]
            if _meta_vals:
                chunk_keywords = chunk_keywords + _meta_vals[:3]

            # 3. 构造 ES 文档
            doc = {
                "_op_type": "index",
                "_index": INDEX_NAME,
                "_id": doc_id,
                "_source": {
                    # [P0-5A] content 存于 BM25 全文检索，同时也是向量化的基准文本
                    "content":         chunk.raw_content if chunk.raw_content else chunk.content,
                    # display_content 含面包屑+overlap，供前端展示和提供上下文线索
                    "display_content": chunk.content,
                    "vector":           vector,
                    # [Sparse] BGE-M3 稀疏向量：{token: weight} 字典，写入 ES rank_features 字段
                    # 降级时为空字典，ES rank_features 字段不写入空值（不影响稠密检索）
                    **({"sparse_vector": sparse_vector} if sparse_vector else {}),
                    "chunk_granularity": granularity,
                    "parent_chunk_id":  parent_chunk_id,
                    "keywords":         chunk_keywords,   # [P1-3A] chunk 级独立关键词
                    "metadata": {
                        "source":           source_name,
                        "title":            doc_title,   # [标题检索修复] 文档标题，独立 text 字段支持分词检索
                        "chunk_id":         original_i,
                        "chunk_type":       chunk.chunk_type,
                        "section_path":     chunk.section_path,
                        "quality_score":    chunk.quality_score,
                        "is_latest":        False,
                        "doc_version":      new_version,
                        "version_at":       version_at,
                        "updated_by":       uploader_id,
                        **dept_levels,
                        "visibility":       visibility,
                        "acl_tokens":       acl_tokens,
                        "access_groups":    access_groups,
                        "uploader_id":      uploader_id,
                        "tags_kw":          tags_kw,
                        "tags":             ext_metadata.get("tag") if ext_metadata else None,
                        "publish_time":     ext_metadata.get("publishTime") if ext_metadata else None,
                        "document_number":  ext_metadata.get("docNumber") if ext_metadata else None,
                        "dynamic_meta":     doc_meta,
                        "owner":            ext_metadata.get("owner") if ext_metadata else None,
                        "search_queries":   ext_metadata.get("searchQueries") if ext_metadata else None,
                        "data_source":      ext_metadata.get("data_source", "document") if ext_metadata else "document",
                        "owner_dept_id":    dept_levels.get("dept_code_full") or "global",
                        "doc_type":         doc_type,   # [Task7] 文档类型（法规/通知/报告/新闻/会议纪要/通用）
                    }
                }
            }

            actions.append(doc)

        # [架构重构阶段四] bulk写入委托给 DocIndexer
        _bulk_ok, _bulk_final_fail = self.doc_indexer.bulk_write(actions, source_name)

        # [架构重构阶段四] 写入成功后才标记历史版本过期（P0-7B 修复）
        # [2PC 重构] Python 端不再负责 expire_old，全权交由 Java 端回调 register 接口时进行 2PC 原子切换。

        t3 = time.time()
        total_chunks = len(all_chunk_pairs)
        print(f"[{os.path.basename(file_path)}] 向量化+写入 ES 耗时: {t3 - t2:.3f} 秒 (存入 {total_chunks} 个切片)")
        print(f"✅ [{os.path.basename(file_path)}] 处理完成 | 全链路总耗时: {t3 - t0:.3f} 秒")

        # [方案H 废除] colloquial_vector 后台异步回填线程已移除
        # 根因：colloquial_vector 字段的 KNN 搜索在 Java 端已废弃（fetchColloquialResults），
        #       保留回填线程为无意义操作，且每个 chunk 会额外触发一次 LLM 调用（20-40s）。
        # 替代：同义词扩展（方案F/X1）在 BM25 层覆盖口语化检索需求，无需独立向量字段。

        # [Bug-19 修复] Q&A 生成任务推入独立 Redis 队列
        # 根因：原 daemon=True 线程在主进程重启/崩溃时默默消失，Q&A 任务永久丢失无感知。
        # 修复：推入独立 Redis 队列 QUEUE_QA，任务持久化，不随进程重启丢失。
        # 降级 fallback：若 Redis 推送失败，自动回退到后台线程模式。
        if fine_chunks:
            _qa_pushed = False
            try:
                import redis as _redis_mod, json as _json_mod
                _r = _redis_mod.Redis(
                    host     = os.getenv("REDIS_HOST",     "redis"),
                    port     = int(os.getenv("REDIS_PORT", 6379)),
                    password = os.getenv("REDIS_PASSWORD") or None,
                    decode_responses = True
                )
                _qa_payload = _json_mod.dumps({
                    "type":          "QA_GENERATION",
                    "source_name":   source_name,
                    "file_base_hash": file_base_hash,
                    "acl_tokens":    acl_tokens,
                    # 取前 50 个 fine chunk（限制 payload 体积）
                    # 内容是条文/短句级，单个 chunk 内容一般 < 500 字
                    "fine_chunks": [
                        {
                            "content":      c.content,
                            "raw_content":  c.raw_content or c.content,
                            "section_path": c.section_path,
                        }
                        for c in fine_chunks[:50]
                    ]
                }, ensure_ascii=False)
                _r.lpush("QUEUE_QA", _qa_payload)
                _r.close()
                _qa_pushed = True
                print(f"📋 [Q&A] 任务已推入 QUEUE_QA ({min(len(fine_chunks), 50)} 个 chunk)")
            except Exception as _qa_push_err:
                print(f"⚠️ [Q&A] 推入队列失败，降级为后台线程: {_qa_push_err}")
            if not _qa_pushed:
                def _bg_qa_fallback():
                    try:
                        self._generate_and_index_qa_pairs(fine_chunks, source_name, file_base_hash, acl_tokens)
                    except Exception as _qa_err:
                        print(f"⚠️ [Q&A] 生成失败: {_qa_err}")
                threading.Thread(target=_bg_qa_fallback, daemon=False).start()

        # [架构重构阶段四] 元数据更新委托给 DocIndexer
        try:
            data_src = ext_metadata.get("data_source", "document") if ext_metadata else "document"
            self.doc_indexer.update_doc_meta(source_name, actions, data_src, content_hash=content_hash, acl_tokens=acl_tokens)
        except Exception as meta_err:
            print(f"⚠️ [DocMeta] 更新 kb_doc_meta 失败（不影响主索引）: {meta_err}")

        # [C5] 权限事件回调 & [文档注册] 写入 kb_doc_registry
        # [Task 4] 将高延迟的跨服务 HTTP 调用转入后台纯异步线程，防止阻塞主流响应
        def bg_notify_java():
            try:
                import requests as _rq
                java_host = os.getenv("JAVA_SERVICE_HOST", "http://127.0.0.1:8080")
                internal_token = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
                perm_payload = {
                    "sourceName":  source_name,
                    "docVersion":  new_version,
                    "contentHash": content_hash,
                    "chunkCount":  len(actions),
                    "uploaderId":  uploader_id,
                    "visibility":  visibility,
                    "deptCode":    ext_metadata.get("dept_code") if ext_metadata else None
                }
                resp = _rq.post(f"{java_host}/api/doc/perm/record",
                                json=perm_payload,
                                headers={"X-Internal-Token": internal_token},
                                timeout=5.0)
                if resp.status_code == 200:
                    print(f"✅ [C5 PermEvent] '{source_name}' v{new_version} 权限事件已写入 PG")
                else:
                    print(f"⚠️ [C5 PermEvent] Java 侧返回异常: {resp.status_code}")
            except Exception as perm_err:
                print(f"⚠️ [C5 PermEvent] 权限事件写入失败（ES 入库不受影响）: {perm_err}")

            try:
                import requests as _rq
                java_host = os.getenv("JAVA_SERVICE_HOST", "http://127.0.0.1:8080")
                internal_token = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
                # [storagePath 修复] 提取干净的 bucket/objectKey 路径，不存完整 presigned URL 到数据库。
                # 根因：Java 在 Redis 任务 filePath 中放置完整预签名 URL（供 Python 下载文件），
                #       Python 直接将其作为 storagePath 写入 kb_doc_registry，
                #       导致 generatePresignedUrl() 中的 startsWith("knowledge-base/") 检查永远失败 → 503。
                # 修复：提取 URL 的路径部分（去掉协议+主机+query参数），仅保留 bucket/objectKey。
                import urllib.parse as _urlparse
                _parsed = _urlparse.urlparse(file_path)
                if _parsed.scheme in ('http', 'https'):
                    # URL 路径格式："/knowledge-base/2026/04/uuid.docx" → strip '/' → "knowledge-base/2026/04/uuid.docx"
                    _clean_storage_path = _parsed.path.lstrip('/')
                else:
                    _clean_storage_path = file_path  # 本地路径原样保留
                registry_payload = {
                    "sourceName":   source_name,
                    "docVersion":   new_version,
                    # [T1-8 Outbox] taskId 与 fileBaseHash 供 Java 将 outbox WAITING→READY
                    # OutboxPoller 根据这两个字段执行 ES update_by_query 激活文档
                    "taskId":       ext_metadata.get("task_id", "")  if ext_metadata else "",
                    "fileBaseHash": file_base_hash,
                    # [docId 格式修正] 使用 file_base_hash_vN 格式，与 ES chunk doc_id 前缀保持一致
                    "docId":        f"{file_base_hash}_v{new_version}",
                    # [storagePath 修复] 使用提取的干净路径，不存完整 presigned URL
                    "storagePath":  _clean_storage_path,
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
                    print(f"⚠️ [DocRegistry] Java 侧返回异常: {resp.status_code}")
            except Exception as reg_err:
                print(f"⚠️ [DocRegistry] registry 写入失败（ES 入库不受影响）: {reg_err}")

        # [BUG-11 根治] daemon=False：重启时此线程会等待完成，防止 Java 权限/注册事件静默丢失
        # 根因：daemon=True 的线程在主进程退出时被 OS 强制终止，正在进行的 perm/registry 请求丢失
        #   → MySQL 中权限记录缺失 → 部门用户无法搜索到已成功索引的文档
        threading.Thread(target=bg_notify_java, daemon=False).start()


        return {
            "parseDurationMs": int((t1 - t0) * 1000),
            "chunkDurationMs": int((t3 - t1) * 1000),
            "chunkCount": total_chunks,
            "coarseCount": len(coarse_chunks),
            "fineCount": len(fine_chunks),
            "parseResult": parse_result
        }

    def _extract_doc_title(self, raw_text: str, source_name: str) -> str:
        """
        业务功能：从文档原始文本中提取可读标题，写入 metadata.title 供 BM25 分词检索。

        优先级：
          1. Markdown H1 标题（# 开头）：docx/pdf 结构化解析后的最可靠信号
          2. Markdown H2 标题（## 开头）：H1 不存在时降级
          3. 候选行中优先选含政务标题关键词的行（关于/通知/意见/规定 等）
          4. 候选行中第一个有效行（已过滤文号/收文单位/纯符号行）
          5. 文件名去后缀：最终兜底

        过滤规则（跳过以下类型行）：
          - 文号行：匹配 "X发〔YYYY〕N号" 模式（党政公文标准格式）
          - 收文单位行：以 "：" 结尾，或 "各XX：" 格式（如"各区、各部门："）
          - 纯符号/数字行：无实质文字内容
          - HTML 注释、目录分隔符

        @param raw_text    清洗后文档文本（已去水印/乱码）
        @param source_name 原始文件名（含扩展名），用于最终兜底
        @return 提取的标题字符串（最长 150 字符）
        """
        import re as _re

        if not raw_text:
            return os.path.splitext(source_name)[0][:150]

        lines = raw_text.split('\n')

        # ── 正则预编译（仅在方法内使用，无需类级缓存）────────────────────────────
        # 文号模式：〔YYYY〕N号，前面允许有机构简称（如"政办发"/"XX发"/"财预"）
        _wen_hao = _re.compile(r'[^\s〔]{0,10}〔\s*\d{4}\s*〕\s*\d+\s*号')
        # 收文单位行：以全角/半角冒号结尾，或 "各区、各部门："
        _recipient = _re.compile(r'^各[^，。\n]{1,30}[：:]\s*$|[：:]\s*$')
        # 纯噪声行：全为数字/符号/空白
        _noise = _re.compile(r'^[\d\s\-=_#*|/\\,.，。、；;！!…·]+$')
        # 政务文档标题高频词（命中则该行极大概率是标题）
        _title_kws = ('关于', '通知', '意见', '规定', '办法', '方案', '规划',
                      '计划', '报告', '决定', '公告', '公示', '制度', '细则',
                      '实施', '条例', '规程', '章程', '指引', '指南', '暂行')

        h2_fallback = None
        candidates = []  # 过滤后的候选行列表

        for line in lines[:60]:   # 检查前 60 行（红头公文文号/标题通常在前 10 行内）
            stripped = line.strip()
            if not stripped:
                continue

            # [最优先] Markdown H1 标题（结构化解析产物）
            if stripped.startswith('# '):
                title = stripped[2:].strip()
                if len(title) >= 2:
                    return title[:150]
            # H2 降级备选
            if stripped.startswith('## ') and h2_fallback is None:
                h2_fallback = stripped[3:].strip()[:150]
                continue

            # ── 噪声过滤（被过滤的行不进入 candidates）────────────────────────
            # 1. 文号行（党政公文标准：机构名+〔年份〕+序号+号）
            if _wen_hao.search(stripped):
                continue
            # 2. 收文单位行（各区、各部门：）
            if _recipient.search(stripped):
                continue
            # 3. 纯符号/数字行
            if _noise.match(stripped):
                continue
            # 4. HTML 注释 / 目录分隔符
            if stripped.startswith('<!--') or stripped.startswith('===') or stripped.startswith('---'):
                continue
            # 5. 过短行（< 4 字符，不可能是完整标题）
            if len(stripped) < 4:
                continue

            candidates.append(stripped)

        if h2_fallback:
            return h2_fallback

        if not candidates:
            return os.path.splitext(source_name)[0][:150]

        # ── 从候选行中选择最佳标题 ────────────────────────────────────────────
        # 优先：命中政务标题关键词的行（通常是第一个命中的）
        for cand in candidates[:20]:
            if any(kw in cand for kw in _title_kws):
                return cand[:150]

        # 兜底：第一个候选行（已过滤文号/收文单位）
        return candidates[0][:150]



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
            # [2PC 重构] 只有主数据切片用 True/False 控制，META 属于直接覆盖，设为 True
            "is_latest":    True,
        }
        self.es.index(index=META_INDEX, id=doc_id, body=body)
        print(f"✅ [DocMeta] kb_doc_meta 已同步: '{source_name}' ({len(fine_vectors)} fine chunks → doc_vector)")


    def _generate_and_index_qa_pairs(self, fine_chunks, source_name: str, file_base_hash: str, acl_tokens: list):
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
                            "is_latest":       True,
                            "acl_tokens":      acl_tokens
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
