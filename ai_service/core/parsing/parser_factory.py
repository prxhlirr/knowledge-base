import os
import tempfile
import time
import requests

from core.parsing.html_parser import HtmlParser
from core.parsing.docx_parser import DocxParser
from core.parsing.doc_parser import DocParser
from core.parsing.pdf_parser import PdfParser
from core.parsing.markitdown_parser import MarkItDownParser
from core.parsing.scanned_pdf_parser import ScannedPdfParser           # Phase 2 已激活
from core.parsing.pdf_transforms.pdf_processor import PdfProcessor     # PDF 变换管道
from core.parsing.document_element import DocumentElement, PageParseStatus, ParseReport


class ParserFactory:
    """
    业务功能：根据文件路径（扩展名）选择最合适的 Parser 实例（工厂方法模式）。
    关键流程：
      1. 若 file_path 是 HTTP/HTTPS URL，先下载到本地临时文件
      2. 若文件是 PDF，先经过 PdfProcessor 变换管道（裁页/旋转矫正/OCR 预处理）
      3. 按注册顺序遍历 _parsers，首个 can_parse() 返回 True 的 Parser 执行 parse()
      4. 若专属 Parser 返回空字符串（失败），降级至 MarkItDownParser（兜底）
      5. 所有临时文件（URL下载 + PDF变换）在 finally 中统一清理
    扩展方式：
      - 新增 Parser：实现 AbstractParser 协议并在 _parsers 中注册
      - 新增 PDF 预处理：实现 BaseTransform 并在 PdfProcessor._transforms 中注册
    """

    def __init__(self, md_converter):
        # md_converter 由 RAGPipeline.__init__ 传入，避免重复初始化 MarkItDown
        self._parsers = [
            HtmlParser(),                      # .html/.htm 优先处理（政府网站公告格式）
            DocxParser(),                      # .docx 结构感知解析
            DocParser(),                       # .doc 五路责任链
            ScannedPdfParser(),                # [Phase 2 已激活] 扫描件 OCR 路径（识别 _ocr_result.txt）
            PdfParser(),                       # .pdf 坐标行重建（pdfplumber，普通 PDF）
        ]
        self._fallback = MarkItDownParser(md_converter)   # 通用兜底
        self._pdf_processor = PdfProcessor()              # PDF 变换管道（Phase1裁页/Phase2旋转矫正/OCR）

    def parse(self, file_path: str, parse_options: dict = None) -> str:
        """
        业务功能：统一文件解析入口，集成 PDF 变换管道和自动降级兜底。
        关键流程：URL下载 → [PDF变换管道] → 格式路由 → 专属解析 → 失败降级 → 清理临时文件
        parse_options 扩展字典（可选）：
          skip_pages (int, 默认 0)：物理跳过 PDF 前 N 页（PageSkipTransform 激活条件）
          scanned (bool, 默认 False)：Phase 2 全图像解析模式（DeskewTransform + OcrPipeline 激活条件）
        """
        opts = parse_options or {}
        local_path = file_path
        temp_local = None
        temp_pdfs = []   # 收集 PdfProcessor 产生的所有临时文件路径

        try:
            # 步骤1：URL 下载到本地临时文件
            if file_path.startswith("http://") or file_path.startswith("https://"):
                local_path, temp_local = self._download_url(file_path)

            # [PDF变换管道] 步骤1.5：PDF 内容变换预处理
            # 职责完全委托给 PdfProcessor，ParserFactory 不关心具体变换实现。
            # Phase 1（已实现）：PageSkipTransform（skip_pages > 0 时激活，裁去红头首页）
            # Phase 2（已实现）：ScannedDetectorTransform（所有PDF均执行，探测扫描件写 opts["scanned"]）
            # Phase 2（已实现）：DeskewTransform（scanned=True 时激活，旋转矫正）
            # Phase 2（已实现）：OcrPipelineTransform（scanned=True 时激活，PaddleOCR PP-Structure）
            # 扫描件路由：OcrPipeline 输出 _ocr_result.txt → ScannedPdfParser 接管
            if local_path.lower().endswith(".pdf"):
                local_path, temp_pdfs = self._pdf_processor.process(local_path, opts)

            # 步骤2：路由到专属 Parser
            for parser in self._parsers:
                if parser.can_parse(local_path):
                    try:
                        result = parser.parse(local_path)
                    except ImportError as ie:
                        # [BUG修复] pdfplumber 未安装时抛 ImportError，
                        # 原外层 except Exception 会捕获并返回"系统脱机保护"错误前缀而非降级。
                        # 修复：内层单独捕获 ImportError，降级至 MarkItDown，不阻断入库流程。
                        print(f"[ParserFactory] {type(parser).__name__} 依赖缺失，降级至 MarkItDown: {ie}")
                        result = ""
                    if result:
                        return result
                    print(f"[ParserFactory] {type(parser).__name__} 返回空，降级至 MarkItDownParser")
                    break

            # 步骤3：兜底解析（MarkItDown）
            return self._fallback.parse(local_path)

        except Exception as _err:
            print(f"⚠️ [ParserFactory] 解析包装层触发不可控异常: {_err}")
            return f"【系统脱机保护】：前置转码遭遇致命异常（报错提示：{str(_err)}）。"
        finally:
            # 步骤4：统一清理所有临时文件
            # URL下载临时文件（temp_local）+ PdfProcessor 产生的临时文件（temp_pdfs）均在此清理
            for tmp in ([temp_local] + temp_pdfs):
                if tmp and os.path.exists(tmp):
                    try:
                        os.remove(tmp)
                    except Exception:
                        pass

    def parse_elements(self, file_path: str, parse_options: dict = None) -> list:
        """统一结构化解析入口；旧 parser 自动包装为 paragraph 元素。"""
        opts = parse_options or {}
        local_path = file_path
        temp_local = None
        temp_pdfs = []
        is_pdf_input = file_path.lower().endswith(".pdf")
        parse_started = time.time()

        try:
            if file_path.startswith("http://") or file_path.startswith("https://"):
                local_path, temp_local = self._download_url(file_path)

            if local_path.lower().endswith(".pdf"):
                local_path, temp_pdfs = self._pdf_processor.process(local_path, opts)

            for parser in self._parsers:
                if parser.can_parse(local_path):
                    try:
                        if hasattr(parser, "parse_elements"):
                            elements = parser.parse_elements(local_path)
                            if elements:
                                if is_pdf_input:
                                    self._store_pdf_report(opts, file_path, local_path, elements, type(parser).__name__, parse_started)
                                return elements
                        result = parser.parse(local_path)
                    except ImportError as ie:
                        print(f"[ParserFactory] {type(parser).__name__} 依赖缺失，降级至 MarkItDown: {ie}")
                        result = ""
                    if result:
                        elements = self._wrap_text_elements(result, type(parser).__name__)
                        if is_pdf_input:
                            self._store_pdf_report(opts, file_path, local_path, elements, type(parser).__name__, parse_started)
                        return elements
                    print(f"[ParserFactory] {type(parser).__name__} 结构化返回空，降级至 MarkItDownParser")
                    break

            elements = self._wrap_text_elements(self._fallback.parse(local_path), "markitdown")
            if is_pdf_input:
                self._store_pdf_report(opts, file_path, local_path, elements, "markitdown", parse_started)
            return elements

        except Exception as _err:
            print(f"⚠️ [ParserFactory] 结构化解析包装层触发不可控异常: {_err}")
            return [DocumentElement(
                text=f"【系统脱机保护】：前置转码遭遇致命异常（报错提示：{str(_err)}）。",
                type="paragraph",
                source="parser_factory",
            )]
        finally:
            for tmp in ([temp_local] + temp_pdfs):
                if tmp and os.path.exists(tmp):
                    try:
                        os.remove(tmp)
                    except Exception:
                        pass
                json_tmp = tmp + ".elements.json" if tmp else None
                if json_tmp and os.path.exists(json_tmp):
                    try:
                        os.remove(json_tmp)
                    except Exception:
                        pass

    def _wrap_text_elements(self, text: str, source: str) -> list:
        if not text:
            return []
        elements = []
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            typ = "title" if stripped.startswith("#") else "table" if stripped.startswith("|") else "paragraph"
            elements.append(DocumentElement(text=stripped, type=typ, source=str(source).lower()))
        return elements

    def _store_pdf_report(self, opts: dict, source_path: str, parsed_path: str, elements: list, parser_name: str, started_at: float) -> None:
        report_ctx = (opts or {}).get("_report_ctx")
        if not isinstance(report_ctx, dict):
            return

        existing = report_ctx.get("report")
        page_chars = {}
        for el in elements or []:
            page_idx = getattr(el, "page_idx", None)
            if page_idx is None:
                continue
            try:
                page_idx = int(page_idx)
            except Exception:
                continue
            page_chars[page_idx] = page_chars.get(page_idx, 0) + len((getattr(el, "text", "") or "").strip())

        if not page_chars and elements:
            page_total = 1
            page_chars[0] = sum(len((getattr(el, "text", "") or "").strip()) for el in elements or [])
        else:
            page_total = self._count_pdf_pages(parsed_path if str(parsed_path).lower().endswith(".pdf") else source_path)

        new_success_pages = sum(1 for count in page_chars.values() if int(count) > 0)
        if existing is not None and getattr(existing, "details", None):
            existing.compute_stats()
            if existing.page_parsed > 0 or new_success_pages <= 0:
                return

        if page_total <= 0 and page_chars:
            page_total = max(page_chars) + 1
        if page_total <= 0:
            page_total = 1 if elements else 0

        duration_ms = int((time.time() - started_at) * 1000)
        parser_type = self._report_parser_type(parser_name, parsed_path)
        details = []
        for idx in range(page_total):
            char_count = int(page_chars.get(idx, 0))
            status = "SUCCESS" if char_count > 0 else "EMPTY"
            details.append(PageParseStatus(
                page_idx=idx,
                status=status,
                parser_type=parser_type,
                char_count=char_count,
                duration_ms=duration_ms // max(page_total, 1),
                error_msg="" if char_count > 0 else "no text extracted for page",
            ))

        report = ParseReport(source_name=os.path.basename(source_path), details=details)
        report.compute_stats()
        report_ctx["report"] = report

    def _count_pdf_pages(self, file_path: str) -> int:
        try:
            import fitz
            doc = fitz.open(file_path)
            count = int(doc.page_count)
            doc.close()
            return count
        except Exception:
            pass
        try:
            import pdfplumber
            with pdfplumber.open(file_path) as pdf:
                return len(pdf.pages)
        except Exception:
            return 0

    def _report_parser_type(self, parser_name: str, parsed_path: str) -> str:
        if str(parsed_path).endswith("_ocr_result.txt") or "Scanned" in str(parser_name):
            return "OCR"
        if "PdfParser" in str(parser_name):
            return "TEXT_LAYER"
        return str(parser_name or "UNKNOWN").upper()

    def _download_url(self, url: str):
        """
        业务功能：将 HTTP/HTTPS URL 下载为本地临时文件。
        关键流程：流式下载（64KB 分块）→ 从 URL 路径推断扩展名（去除预签名参数）
        返回：(local_path, temp_file_path)；失败时返回 (url, None)
        """
        try:
            resp = requests.get(url, timeout=60, stream=True)
            resp.raise_for_status()
            raw_suffix = url.split("?")[0].rsplit(".", 1)
            suffix = f".{raw_suffix[-1].lower()}" if len(raw_suffix) > 1 else ".bin"
            with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
                for chunk in resp.iter_content(chunk_size=65536):
                    tmp.write(chunk)
                temp_local = tmp.name
            print(f"[ParserFactory] URL 已下载到临时文件: {temp_local} (ext={suffix})")
            return temp_local, temp_local
        except Exception as _err:
            print(f"⚠️ [ParserFactory] URL 下载失败，尝试直接传递给 MarkItDown: {_err}")
            return url, None  # 回退：直接传 URL 给 MarkItDown
