from __future__ import annotations

from dataclasses import dataclass, field, replace
import re
from typing import Any, Dict, List, Optional


@dataclass
class PageParseStatus:
    """页级解析状态模型，用于追溯单页的解析情况。"""
    page_idx: int
    status: str            # "SUCCESS" (成功), "FAILED" (失败), "EMPTY" (空白无有效字)
    parser_type: str       # "TEXT_LAYER" (原生文字层), "OCR_STRUCTURE" (PP-Structure版面分析), "OCR_PURE" (纯OCR兜底)
    char_count: int        # 解析到的字符数
    duration_ms: int       # 耗时（毫秒）
    error_msg: str = ""    # 异常错误信息


@dataclass
class ParseReport:
    """文档级解析质量报告模型，用于质量门控及上报。"""
    source_name: str
    page_total: int = 0
    page_parsed: int = 0
    page_failed: int = 0
    coverage_ratio: float = 0.0
    details: List[PageParseStatus] = field(default_factory=list)

    def compute_stats(self):
        """计算解析指标，包含总页数、成功数、失败数及覆盖率。"""
        self.page_total = len(self.details)
        self.page_parsed = sum(1 for d in self.details if d.status == "SUCCESS")
        self.page_failed = sum(1 for d in self.details if d.status == "FAILED")
        self.coverage_ratio = float(self.page_parsed) / float(self.page_total) if self.page_total > 0 else 0.0

    def to_dict(self) -> Dict[str, Any]:
        """将报告序列化为纯 dict，便于 JSON 传输及存储。"""
        return {
            "source_name": self.source_name,
            "page_total": self.page_total,
            "page_parsed": self.page_parsed,
            "page_failed": self.page_failed,
            "coverage_ratio": round(self.coverage_ratio, 4),
            "details": [
                {
                    "page_idx": d.page_idx,
                    "status": d.status,
                    "parser_type": d.parser_type,
                    "char_count": d.char_count,
                    "duration_ms": d.duration_ms,
                    "error_msg": d.error_msg
                } for d in self.details
            ]
        }



@dataclass
class DocumentElement:
    """Structured text unit produced by parsers before indexing decisions."""

    text: str
    type: str = "paragraph"
    page_idx: Optional[int] = None
    bbox: Optional[List[float]] = None
    source: str = "unknown"
    keep_for_index: bool = True
    keep_for_metadata: bool = False
    noise_reason: Optional[str] = None
    meta: Dict[str, Any] = field(default_factory=dict)

    def normalized_text(self) -> str:
        return re.sub(r"\s+", " ", (self.text or "").strip())

    def clone_with(self, **changes: Any) -> "DocumentElement":
        return replace(self, **changes)


def elements_to_text(elements: List[DocumentElement], *, metadata: bool = False) -> str:
    lines: List[str] = []
    current_page = -1
    for el in elements:
        keep = el.keep_for_metadata if metadata else el.keep_for_index
        if not keep:
            continue
        text = (el.text or "").strip()
        if text:
            # 当页码发生变化时，注入物理页边界锚点（跳过 metadata 路径以保持纯净）
            # 注释中的页码即为原始 page_idx（从 0 开始计，便于与 ES 和后端索引映射对齐）
            if not metadata and el.page_idx is not None and el.page_idx != current_page:
                current_page = el.page_idx
                lines.append(f"\n<!-- PAGE_START: {current_page} -->\n")
            lines.append(text)
    return "\n".join(lines).strip()
