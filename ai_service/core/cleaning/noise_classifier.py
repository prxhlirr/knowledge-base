from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, field
import os
import re
from typing import Dict, Iterable, List, Optional

from core.parsing.document_element import DocumentElement, elements_to_text


@dataclass
class NoiseClassifyResult:
    elements: List[DocumentElement]
    index_text: str
    metadata_text: str
    noise_report: Dict = field(default_factory=dict)


class NoiseClassifier:
    """Classify parser elements into indexable text, metadata text, and noise."""

    _PAGE_PATTERNS = [
        re.compile(r"^\s*第\s*\d+\s*页\s*(共\s*\d+\s*页)?\s*$"),
        re.compile(r"^\s*page\s*\d+\s*(of|/)\s*\d+\s*$", re.I),
        re.compile(r"^\s*[-–—]?\s*\d+\s*[-–—]?\s*$"),
        re.compile(r"^\s*\d+\s*/\s*\d+\s*$"),
    ]
    _DOC_META_RE = re.compile(
        r"(发文|文号|签发|发布|发布日期|印发|机关|政府|委员会|办公室|局|部|密级|秘密|机密|绝密|〔\d{4}〕|规〔|函〔)"
    )
    _DATE_RE = re.compile(r"\d{4}\s*[年/-]\s*\d{1,2}\s*[月/-]\s*\d{1,2}\s*日?")
    _NUM_RE = re.compile(r"\d+")

    def __init__(self, cleaner=None):
        self.cleaner = cleaner
        self.comment_policy = os.getenv("COMMENT_INDEX_POLICY", "drop").lower()
        self.footnote_policy = os.getenv("FOOTNOTE_INDEX_POLICY", "metadata_only").lower()
        self.pdf_top_ratio = float(os.getenv("PDF_HEADER_FOOTER_TOP_RATIO", "0.10"))
        self.pdf_bottom_ratio = float(os.getenv("PDF_HEADER_FOOTER_BOTTOM_RATIO", "0.10"))
        self.pdf_min_repeat = int(os.getenv("PDF_HEADER_FOOTER_MIN_REPEAT", "2"))

    def classify(self, elements: Iterable[DocumentElement], options: Optional[dict] = None) -> NoiseClassifyResult:
        opts = options or {}
        prepared = [self._clean_element(el) for el in elements if (el.text or "").strip()]
        prepared = self._classify_structural(prepared)
        if opts.get("pdf_noise_filter_enabled", os.getenv("PDF_NOISE_FILTER_ENABLED", "true").lower() == "true"):
            prepared = self._classify_pdf_repeated_margins(prepared)

        index_text = elements_to_text(prepared, metadata=False)
        metadata_text = elements_to_text(prepared, metadata=True)
        report = self._build_report(prepared)
        return NoiseClassifyResult(prepared, index_text, metadata_text, report)

    def _clean_element(self, el: DocumentElement) -> DocumentElement:
        text = el.text or ""
        if self.cleaner and hasattr(self.cleaner, "clean_line"):
            text = self.cleaner.clean_line(text)
        else:
            text = re.sub(r"[ \t]+", " ", text).strip()
        return el.clone_with(text=text)

    def _classify_structural(self, elements: List[DocumentElement]) -> List[DocumentElement]:
        out: List[DocumentElement] = []
        for el in elements:
            typ = (el.type or "paragraph").lower()
            text = el.normalized_text()
            if not text:
                continue
            if typ == "header":
                out.append(el.clone_with(
                    keep_for_index=False,
                    keep_for_metadata=True,
                    noise_reason="word_header_metadata",
                ))
            elif typ == "footer":
                out.append(el.clone_with(
                    keep_for_index=False,
                    keep_for_metadata=bool(self._DOC_META_RE.search(text)) and not self._is_page_number(text),
                    noise_reason="word_footer",
                ))
            elif typ == "comment":
                out.append(self._apply_policy(el, self.comment_policy, "comment"))
            elif typ in ("footnote", "endnote"):
                out.append(self._apply_policy(el, self.footnote_policy, typ))
            elif typ == "watermark":
                out.append(el.clone_with(keep_for_index=False, keep_for_metadata=False, noise_reason="watermark"))
            else:
                out.append(el)
        return out

    def _apply_policy(self, el: DocumentElement, policy: str, reason: str) -> DocumentElement:
        if policy == "index":
            return el.clone_with(keep_for_index=True, keep_for_metadata=True)
        if policy == "metadata_only":
            return el.clone_with(keep_for_index=False, keep_for_metadata=True, noise_reason=f"{reason}_metadata_only")
        return el.clone_with(keep_for_index=False, keep_for_metadata=False, noise_reason=reason)

    def _classify_pdf_repeated_margins(self, elements: List[DocumentElement]) -> List[DocumentElement]:
        pdf_like = [el for el in elements if el.page_idx is not None and el.bbox and el.source in ("pdf", "ocr")]
        if not pdf_like:
            return elements

        page_heights = self._estimate_page_heights(pdf_like)
        candidates = []
        for idx, el in enumerate(elements):
            if el.page_idx is None or not el.bbox or el.source not in ("pdf", "ocr"):
                continue
            zone = self._margin_zone(el, page_heights.get(el.page_idx))
            if not zone:
                continue
            norm = self._fingerprint(el.normalized_text())
            if norm:
                candidates.append((idx, zone, norm))

        counts = Counter((zone, norm) for _, zone, norm in candidates)
        indexes_to_filter = set()
        for idx, zone, norm in candidates:
            text = elements[idx].normalized_text()
            if counts[(zone, norm)] >= self.pdf_min_repeat or self._is_page_number(text):
                indexes_to_filter.add(idx)

        if not indexes_to_filter:
            return elements

        out = []
        for idx, el in enumerate(elements):
            if idx in indexes_to_filter and el.keep_for_index:
                out.append(el.clone_with(
                    type="header" if self._margin_zone(el, page_heights.get(el.page_idx)) == "top" else "footer",
                    keep_for_index=False,
                    keep_for_metadata=bool(self._DOC_META_RE.search(el.normalized_text())) and not self._is_page_number(el.normalized_text()),
                    noise_reason="pdf_repeated_margin",
                ))
            else:
                out.append(el)
        return out

    def _estimate_page_heights(self, elements: List[DocumentElement]) -> Dict[int, float]:
        heights: Dict[int, float] = defaultdict(float)
        for el in elements:
            if el.page_idx is None or not el.bbox or len(el.bbox) < 4:
                continue
            heights[el.page_idx] = max(heights[el.page_idx], float(el.bbox[3]))
        return heights

    def _margin_zone(self, el: DocumentElement, page_height: Optional[float]) -> Optional[str]:
        if not el.bbox or len(el.bbox) < 4 or not page_height:
            return None
        y0 = float(el.bbox[1])
        y1 = float(el.bbox[3])
        if y1 <= page_height * self.pdf_top_ratio:
            return "top"
        if y0 >= page_height * (1.0 - self.pdf_bottom_ratio):
            return "bottom"
        return None

    def _fingerprint(self, text: str) -> str:
        text = text.lower().strip()
        if not text:
            return ""
        text = self._DATE_RE.sub("<date>", text)
        text = re.sub(r"第\s*\d+\s*页\s*共\s*\d+\s*页", "page <n> of <m>", text)
        text = re.sub(r"page\s*\d+\s*(of|/)\s*\d+", "page <n> of <m>", text, flags=re.I)
        text = self._NUM_RE.sub("<n>", text)
        text = re.sub(r"\s+", " ", text)
        return text

    def _is_page_number(self, text: str) -> bool:
        return any(p.match(text or "") for p in self._PAGE_PATTERNS)

    def _build_report(self, elements: List[DocumentElement]) -> Dict:
        total_chars = sum(len(el.text or "") for el in elements)
        filtered = [el for el in elements if not el.keep_for_index]
        filtered_chars = sum(len(el.text or "") for el in filtered)
        by_type = Counter((el.type or "unknown") for el in filtered)
        by_reason = Counter((el.noise_reason or "unspecified") for el in filtered)
        return {
            "total_elements": len(elements),
            "index_elements": sum(1 for el in elements if el.keep_for_index),
            "metadata_elements": sum(1 for el in elements if el.keep_for_metadata),
            "filtered_elements": len(filtered),
            "filtered_by_type": dict(by_type),
            "filtered_by_reason": dict(by_reason),
            "total_chars": total_chars,
            "filtered_chars": filtered_chars,
            "filter_ratio": round(filtered_chars / max(total_chars, 1), 4),
        }
