import os
import time
import numpy as np
import urllib.parse
from typing import List
from elasticsearch import Elasticsearch, helpers

from core.indexing.es_setup import INDEX_NAME, QA_INDEX_NAME, DOC_META_WRITE_ALIAS


def _default_acl_tokens() -> list[str]:
    raw = os.getenv("KB_DOC_META_DEFAULT_ACL_TOKENS", "_INTERNAL")
    tokens = [token.strip() for token in raw.split(",") if token.strip()]
    return tokens or ["_INTERNAL"]


class DocIndexer:
    """
    业务功能：将已向量化的 chunk action 列表批量写入 ES，并完成版本管理和文档元数据更新。
    从 RAGPipeline.process_and_index 提取（架构重构阶段四）。

    关键流程：
    1. bulk_write()   - 批量写入 + 失败重试（1秒退避）
    2. update_meta()  - 均值池化 fine chunk 向量 → 写入 kb_doc_meta（供相似文档搜索）
    """

    def __init__(self, es: Elasticsearch):
        self.es = es

    def bulk_write(self, actions: list, source_name: str) -> tuple[int, int]:
        """
        业务功能：执行 ES bulk 写入，失败时自动提取失败 ID 并退避重试一次。
        关键流程：首次 bulk → 提取失败 ID → 1秒退避 → 重试失败条目 → 返回 (成功数, 最终失败数)
        [性能优化] refresh=False（ES 默认）：不强制 segment refresh，避免每次 bulk 后的同步等待。
        根因：refresh=True 要求 ES 写完后立即执行 refresh（50-200ms 同步阻塞），
              入库流水线不需要写完即可搜，ES 默认 1s 自动 refresh 完全满足业务需求。
        """
        _ok, _failed = helpers.bulk(
            self.es, actions, raise_on_error=False, stats_only=False, refresh=False
        )

        if not _failed:
            print(f"✅ [{source_name}] bulk 全部写入成功: {_ok} 条")
            return _ok, 0

        print(f"⚠️ [{source_name}] bulk 写入部分失败: 成功={_ok}, 失败数={len(_failed)}")

        # [Bug-18 修复] 正确提取失败 chunk 的 _id（原代码用 'data' 键，该键不存在，重试逻辑为死代码）
        _failed_ids = set()
        for _err_item in _failed:
            _op_type   = list(_err_item.keys())[0]
            _err_detail = _err_item[_op_type]
            _failed_id  = _err_detail.get('_id')
            _err_reason = _err_detail.get('error', {}).get('reason', 'unknown')
            if _failed_id:
                _failed_ids.add(_failed_id)
                print(f"  ↳ 失败 ID={_failed_id}: {_err_reason[:120]}")

        # 从原始 actions 中找回失败条目，1秒退避后重试
        _retry_actions = [a for a in actions if a.get('_id') in _failed_ids]
        if _retry_actions:
            time.sleep(1.0)
            _r_ok, _r_fail = helpers.bulk(
                self.es, _retry_actions, raise_on_error=False, stats_only=False, refresh=False
            )
            print(f"  [重试] 结果: 成功={_r_ok}, 仍失败={len(_r_fail)}")
            if _r_fail:
                print(f"  ⚠️ [{source_name}] {len(_r_fail)} 个 chunk 重试后仍失败，已记录但不阻断主流程")
            return _ok + _r_ok, len(_r_fail)

        return _ok, len(_failed)



    def update_doc_meta(self, source_name: str, chunk_actions: list,
                        data_source: str = "document", content_hash: str = None,
                        acl_tokens: list = None, doc_id: str = None, doc_version: int = None):
        """
        业务功能：将所有 fine chunk 向量均值池化为 doc_vector，写入 kb_doc_meta（供相似文档搜索）。
        核心原理：doc_vector = mean(fine chunk vectors) → L2 normalize
        幂等：以 source_name 为 _id，重复上传只会改写而不是新增。
        """
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
        arr      = np.array(fine_vectors, dtype=np.float32)
        mean_vec = np.mean(arr, axis=0)
        norm     = np.linalg.norm(mean_vec)
        if norm > 0:
            mean_vec = mean_vec / norm

        first_source = next(
            (action.get("_source", {}) for action in chunk_actions if action.get("_source")),
            {},
        )
        first_meta = first_source.get("metadata", {}) if isinstance(first_source.get("metadata"), dict) else {}
        title = first_meta.get("title") or first_source.get("title") or source_name
        summary_text = first_source.get("content") or ""
        if len(summary_text) > 500:
            summary_text = summary_text[:500]

        meta_es_id = urllib.parse.quote(doc_id or content_hash or source_name, safe="")
        body = {
            "doc_id":       doc_id or "",
            "doc_version":  doc_version,
            "source":       source_name,
            "source_name":  source_name,
            "title":        title,
            "summary":      summary_text,
            "doc_type":     first_meta.get("doc_type") or "",
            "data_source":  data_source,
            "chunk_count":  len(fine_vectors),
            "doc_vector":   mean_vec.tolist(),
            "updated_at":   int(time.time() * 1000),
            "content_hash": content_hash,
            "is_latest":    True,
            "acl_tokens":   acl_tokens or _default_acl_tokens(),
            "visibility":   first_meta.get("visibility") or "",
            "owner_dept_id": first_meta.get("owner_dept_id") or "",
        }
        try:
            self.es.update_by_query(
                index=DOC_META_WRITE_ALIAS,
                body={
                    "script": {"source": "ctx._source.is_latest = false", "lang": "painless"},
                    "query": {"bool": {"filter": [
                        {"term": {"source": source_name}},
                        {"term": {"data_source": data_source}},
                        *([{"term": {"owner_dept_id": first_meta.get("owner_dept_id")}}]
                          if first_meta.get("owner_dept_id") else []),
                    ]}},
                },
                conflicts="proceed",
                refresh=False,
            )
        except Exception as e:
            print(f"  [DocMeta] mark old versions non-latest skipped: {e}")
        self.es.index(index=DOC_META_WRITE_ALIAS, id=meta_es_id, body=body)
        print(f"✅ [DocMeta] {DOC_META_WRITE_ALIAS} 已同步: '{source_name}' ({len(fine_vectors)} fine chunks → doc_vector)")
