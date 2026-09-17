import os
import time
import numpy as np
import urllib.parse
from typing import List
from elasticsearch import Elasticsearch, helpers

from core.indexing.es_setup import INDEX_NAME, QA_INDEX_NAME, DOC_META_WRITE_ALIAS, DOC_SEARCH_WRITE_ALIAS
from core.normalization.text_normalizer import (
    normalize_content_if_fully_encoded,
    normalize_filename,
    normalize_list,
    normalize_metadata_text,
)


def _default_acl_tokens() -> list[str]:
    raw = os.getenv("KB_DOC_META_DEFAULT_ACL_TOKENS", "_NO_ACCESS")
    tokens = [token.strip() for token in raw.split(",") if token.strip()]
    return tokens or ["_NO_ACCESS"]


def _as_list(value) -> list:
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        return [v for v in value if v is not None and str(v).strip()]
    text = str(value).strip()
    return [text] if text else []


def _add_unique(target: list, value, limit: int = 200):
    for item in _as_list(value):
        item = str(item).strip()
        if item and item not in target:
            target.append(item)
            if len(target) >= limit:
                return


def _physical_index(index_name: str) -> str:
    index_name = (index_name or "").strip()
    if index_name.endswith("_write"):
        return index_name[:-len("_write")]
    return index_name


def _index_code(index_name: str) -> str:
    physical = _physical_index(index_name)
    prefix = "kb_document_"
    if physical.startswith(prefix):
        return physical[len(prefix):]
    return physical


def _visible_unit_codes(meta: dict) -> list:
    values = []
    for key in ("visible_unit_codes", "visible_depts", "owner_dept_id"):
        _add_unique(values, meta.get(key), 64)
    return values


def _permission_version(meta: dict) -> int:
    try:
        return int(meta.get("permission_version") or 0)
    except (TypeError, ValueError):
        return 0


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

    def _index_with_optional_fields(self, index: str, id: str, body: dict, optional_fields: list[str]):
        try:
            return self.es.index(index=index, id=id, body=body)
        except Exception as exc:
            message = str(exc)
            if "strict_dynamic_mapping_exception" not in message:
                raise
            fallback = {k: v for k, v in body.items() if k not in optional_fields}
            print(f"  [DocIndexer] strict mapping lacks optional fields {optional_fields}; retry without them")
            return self.es.index(index=index, id=id, body=fallback)

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
                        acl_tokens: list = None, doc_id: str = None, doc_version: int = None,
                        source_index: str = None):
        """
        业务功能：将所有 fine chunk 向量均值池化为 doc_vector，写入 kb_doc_meta（供相似文档搜索）。
        核心原理：doc_vector = mean(fine chunk vectors) → L2 normalize
        幂等：以 source_name 为 _id，重复上传只会改写而不是新增。
        """
        source_name = normalize_filename(source_name)
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
        physical_index = _physical_index(source_index or first_source.get("_index") or "")
        owner_unit_code = first_meta.get("owner_unit_code") or first_meta.get("owner_dept_id") or ""
        title = normalize_metadata_text(first_meta.get("title") or first_source.get("title") or source_name)
        summary_text = normalize_content_if_fully_encoded(first_source.get("content") or "")
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
            "source_index":  physical_index,
            "index_code":    _index_code(physical_index),
            "owner_unit_code": owner_unit_code,
            "visible_unit_codes": _visible_unit_codes(first_meta),
            "permission_version": _permission_version(first_meta),
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
        self._index_with_optional_fields(
            DOC_META_WRITE_ALIAS,
            meta_es_id,
            body,
            [],
        )
        print(f"✅ [DocMeta] {DOC_META_WRITE_ALIAS} 已同步: '{source_name}' ({len(fine_vectors)} fine chunks → doc_vector)")

    def update_doc_search(self, source_name: str, chunk_actions: list,
                          data_source: str = "document", content_hash: str = None,
                          acl_tokens: list = None, doc_id: str = None, doc_version: int = None,
                          source_index: str = None):
        """Write one compact document-level keyword record."""
        source_name = normalize_filename(source_name)
        sources = [a.get("_source", {}) for a in chunk_actions if a.get("_source")]
        if not sources:
            print(f"  [DocSearch] '{source_name}' has no chunks, skipped")
            return

        first_source = sources[0]
        first_meta = first_source.get("metadata", {}) if isinstance(first_source.get("metadata"), dict) else {}
        physical_index = _physical_index(source_index or first_source.get("_index") or "")
        owner_unit_code = first_meta.get("owner_unit_code") or first_meta.get("owner_dept_id") or ""
        title = normalize_metadata_text(first_meta.get("title") or first_source.get("title") or source_name)
        document_number = normalize_metadata_text(first_meta.get("document_number") or first_source.get("document_number") or "")

        keywords, tags, section_titles, entities, representative_chunk_ids = [], [], [], [], []
        term_parts = [source_name, title, document_number]
        summary_parts = []
        ordered_sources = sorted(
            sources,
            key=lambda src: (
                0 if src.get("chunk_granularity") == "fine" else 1,
                -float((src.get("metadata") or {}).get("quality_score") or 0),
            ),
        )

        for src in ordered_sources:
            meta = src.get("metadata", {}) if isinstance(src.get("metadata"), dict) else {}
            _add_unique(keywords, normalize_list(src.get("keywords")), 300)
            _add_unique(keywords, normalize_list(meta.get("tags_kw")), 300)
            _add_unique(tags, normalize_list(meta.get("tags")), 100)
            _add_unique(tags, normalize_list(meta.get("tags_kw")), 100)
            _add_unique(section_titles, normalize_list(meta.get("section_path")), 120)
            _add_unique(entities, normalize_metadata_text(meta.get("owner")), 100)
            _add_unique(entities, normalize_metadata_text(meta.get("document_number")), 100)
            chunk_id = meta.get("chunk_id") or src.get("chunk_id")
            if chunk_id is not None and len(representative_chunk_ids) < 32:
                representative_chunk_ids.append(str(chunk_id))
            content = normalize_content_if_fully_encoded(src.get("display_content") or src.get("content") or "")
            if content:
                if len(summary_parts) < 3:
                    summary_parts.append(content[:500])
                if len(term_parts) < 24:
                    term_parts.append(content[:600])

        doc_terms = normalize_metadata_text(" ".join(str(part) for part in term_parts if part))
        for extra in (keywords[:120], tags[:80], section_titles[:80]):
            if extra:
                doc_terms = f"{doc_terms} {' '.join(extra)}".strip()

        search_es_id = urllib.parse.quote(doc_id or content_hash or source_name, safe="")
        body = {
            "doc_id": doc_id or "",
            "doc_version": doc_version,
            "content_hash": content_hash,
            "source": source_name,
            "source_name": source_name,
            "title": title,
            "document_number": document_number,
            "keywords": keywords,
            "tags": tags,
            "entities": entities,
            "section_titles": section_titles,
            "doc_terms": doc_terms[:12000],
            "summary": normalize_content_if_fully_encoded(" ".join(summary_parts))[:1500],
            "representative_chunk_ids": representative_chunk_ids,
            "doc_type": first_meta.get("doc_type") or "",
            "data_source": data_source,
            "chunk_count": len(sources),
            "is_latest": True,
            "acl_tokens": acl_tokens or first_meta.get("acl_tokens") or first_source.get("acl_tokens") or _default_acl_tokens(),
            "visibility": first_meta.get("visibility") or "",
            "owner_dept_id": first_meta.get("owner_dept_id") or "",
            "source_index": physical_index,
            "index_code": _index_code(physical_index),
            "owner_unit_code": owner_unit_code,
            "visible_unit_codes": _visible_unit_codes(first_meta),
            "permission_version": _permission_version(first_meta),
            "publish_time": first_meta.get("publish_time"),
            "updated_at": int(time.time() * 1000),
        }
        if not body["publish_time"]:
            body.pop("publish_time", None)
        try:
            self.es.update_by_query(
                index=DOC_SEARCH_WRITE_ALIAS,
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
            print(f"  [DocSearch] mark old versions non-latest skipped: {e}")
        self._index_with_optional_fields(
            DOC_SEARCH_WRITE_ALIAS,
            search_es_id,
            body,
            [],
        )
        print(f"[DocSearch] {DOC_SEARCH_WRITE_ALIAS} synced: '{source_name}' ({len(sources)} chunks -> one doc)")
