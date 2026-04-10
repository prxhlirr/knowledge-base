import os
import tempfile
import requests

from core.parsing.html_parser import HtmlParser
from core.parsing.docx_parser import DocxParser
from core.parsing.doc_parser import DocParser
from core.parsing.pdf_parser import PdfParser
from core.parsing.markitdown_parser import MarkItDownParser


class ParserFactory:
    """
    业务功能：根据文件路径（扩展名）选择最合适的 Parser 实例（工厂方法模式）。
    关键流程：
      1. 若 file_path 是 HTTP/HTTPS URL，先下载到本地临时文件
      2. 按注册顺序遍历 _parsers，首个 can_parse() 返回 True 的 Parser 执行 parse()
      3. 若专属 Parser 返回空字符串（失败），降级至 MarkItDownParser（兜底）
      4. 临时文件在 finally 中清理
    扩展方式：只需新增 XxxParser 类并在 __init__ 中注册，主流程零改动。
    """

    def __init__(self, md_converter):
        # md_converter 由 RAGPipeline.__init__ 传入，避免重复初始化 MarkItDown
        self._parsers = [
            HtmlParser(),                      # .html/.htm 优先处理（政府网站公告格式）
            DocxParser(),                      # .docx 结构感知解析
            DocParser(),                       # .doc 五路责任链
            PdfParser(),                       # .pdf 坐标行重建（D2，pdfplumber）
        ]
        self._fallback = MarkItDownParser(md_converter)  # 通用兜底

    def parse(self, file_path: str) -> str:
        """
        业务功能：统一文件解析入口，自动完成 URL 下载 + Parser 选择 + 降级兜底。
        入参为 URL 时自动下载到临时文件，函数退出时清理。
        关键流程：URL下载 → 格式路由 → 专属解析 → 失败降级 → 清理临时文件
        """
        local_path = file_path
        temp_local = None

        try:
            # 步骤1：URL 下载到本地临时文件
            if file_path.startswith("http://") or file_path.startswith("https://"):
                local_path, temp_local = self._download_url(file_path)

            # 步骤2：路由到专属 Parser
            for parser in self._parsers:
                if parser.can_parse(local_path):
                    result = parser.parse(local_path)
                    if result:
                        return result
                    # 专属 Parser 失败（返回空），降级至兜底
                    print(f"[ParserFactory] {type(parser).__name__} 返回空，降级至 MarkItDownParser")
                    break

            # 步骤3：兜底解析（MarkItDown）
            return self._fallback.parse(local_path)

        except Exception as _err:
            print(f"⚠️ [ParserFactory] 解析包装层触发不可控异常: {_err}")
            return f"【系统脱机保护】：前置转码遭遇致命异常（报错提示：{str(_err)}）。"
        finally:
            # 步骤4：清理临时文件
            if temp_local and os.path.exists(temp_local):
                try:
                    os.remove(temp_local)
                except Exception:
                    pass

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
