import hashlib
import os

from elasticsearch import Elasticsearch
from core.indexing.es_setup import DOC_META_READ_ALIAS


# [T6-1] 主文档索引名（is_latest=true 的文档在此索引可查）
# 与 rag_pipeline.py 中 target_index 保持一致
_DEFAULT_DOC_INDEX = os.getenv("ES_DOC_INDEX", "kb_document_v1")


class ContentDedupChecker:
    """
    [T6-1 升级] 基于 content_hash 为主键的文档去重检测。

    去重策略（三级优先级）：
      ① Java 预计算的 SHA-256（ext_metadata["content_hash"]），Web/SFTP/URL 路径均已支持
      ② 本地文件二进制前 8KB SHA-256（直接调用/批量脚本的本地路径场景）
      ③ 正文前 2000 字文本 SHA-256 兜底（永不 NameError，变量 raw_text 此时已定义）

    去重查询逻辑（T6-1 主键化）：
      - [新] 优先查询主文档索引（kb_document_v1）中 is_latest=true 的 chunk
        → 仅命中 Outbox 已激活（DONE）的文档，临时写入中（WAITING/READY）不计入去重
        → 保证 content_hash 是已完整入库的内容的指纹，而非临时状态
      - [兜底] 其次查询 kb_doc_meta 索引（快速查，但可能包含未激活记录）
      - must_not source_name = 当前文档（允许同名文件覆盖上传）
      - force_reindex=true 时跳过去重，强制重新入库

    关键安全原则：
      宁可"误过"（把新文档当重复而跳过），不可"误入"（把已存在文档再次写入导致版本污染）。
      因此查询条件故意保守（必须 is_latest=true 且 Outbox 已激活）。
    """

    def __init__(self, es: Elasticsearch, doc_index: str = None):
        self.es        = es
        self.doc_index = doc_index or _DEFAULT_DOC_INDEX

    def compute_hash(self, file_path: str, raw_text: str, ext_metadata: dict) -> tuple[str, str]:
        """
        业务功能：按三级优先级计算内容指纹，返回 (content_hash, 来源说明)。
        关键流程：Java预计算 > 本地二进制前8KB > 文本前2000字兜底
        """
        _java_hash = (ext_metadata or {}).get("content_hash")
        if _java_hash:
            return _java_hash, "Java预计算"

        if file_path and os.path.isfile(file_path):
            try:
                with open(file_path, "rb") as _fh:
                    _file_hash = hashlib.sha256(_fh.read(8192)).hexdigest()
                return _file_hash, "本地文件"
            except Exception as _he:
                print(f"⚠️ [ContentHash] 文件读取失败: {_he}")

        # 兜底：文本前 2000 字计算 hash（不会 NameError，raw_text 此时已定义）
        _text_hash = hashlib.sha256(
            (raw_text[:2000] if raw_text else "").encode("utf-8", errors="ignore")
        ).hexdigest()
        return _text_hash, "文本兜底"

    def is_duplicate(self, source_name: str, content_hash: str, force_reindex: bool = False) -> tuple[bool, str]:
        """
        [T6-1] 以 content_hash 为主键判断文档是否已存在。
        业务功能：返回 (is_dup: bool, existing_source_name: str)。
        关键流程：
          1. force_reindex=True 时直接放行（跳过去重）
          2. 优先查 kb_document_v1（is_latest=true），仅命中已激活的文档
          3. 兜底查 kb_doc_meta（速度快，但包含临时状态记录）
          4. 查询异常时不阻塞主流程（返回 False，允许继续）
        """
        if force_reindex:
            return False, ""

        # [T6-1 主键化] Step1：查主文档索引（is_latest=true），精确命中已激活文档
        try:
            resp = self.es.search(
                index=self.doc_index,
                body={
                    "query": {
                        "bool": {
                            "must": [
                                # [T6-1] content_hash 全量匹配（keyword 类型，精确碰撞）
                                {"term": {"content_hash": content_hash}},
                                # [T6-1] 仅命中 Outbox 已激活（is_latest=true）的文档
                                # OutboxPoller 执行 update_by_query 后才置 true，排除 WAITING/READY 阶段
                                {"term": {"metadata.is_latest": True}}
                            ],
                            "must_not": [
                                # 允许同名文件覆盖上传（source_name 相同则不认为是重复）
                                {"term": {"metadata.source": source_name}}
                            ]
                        }
                    },
                    "_source": ["metadata.source"],
                    # 仅需第一条，不需要排序
                    "size": 1,
                    # 只查一个 shard，减少网络开销
                    "terminate_after": 1
                },
                ignore_unavailable=True
            )
            hits = resp.get("hits", {}).get("hits", [])
            if hits:
                _src = hits[0].get("_source", {}).get("metadata", {})
                existing = _src.get("source", "unknown")
                print(f"🔍 [T6-1 Dedup] content_hash 命中已激活文档：'{existing}'，跳过 '{source_name}'")
                return True, existing
        except Exception as e:
            print(f"⚠️ [T6-1 Dedup] 主索引去重查询失败（兜底查 kb_doc_meta）: {e}")

        # Step2：兜底查 kb_doc_meta（快速查，但可能包含未激活的临时记录）
        try:
            dup_resp = self.es.search(
                index=DOC_META_READ_ALIAS,
                body={
                    "query": {
                        "bool": {
                            "must": [
                                {"term": {"content_hash": content_hash}},
                                {"term": {"is_latest": True}}
                            ],
                            "must_not": [
                                {"term": {"source_name": source_name}}
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
                print(f"🔍 [T6-1 Dedup] kb_doc_meta 命中重复文档：'{existing}'，跳过 '{source_name}'")
                return True, existing
        except Exception as e:
            # 兜底查询也失败时不阻塞主流程
            print(f"⚠️ [T6-1 Dedup] kb_doc_meta 去重查询失败（已跳过）: {e}")

        return False, ""
