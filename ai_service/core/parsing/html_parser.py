import os
import re


class HtmlParser:
    """
    业务功能：解析 .html/.htm 文件，将 HTML 正文转换为 Markdown 格式文本。
    关键流程：
      1. chardet 检测编码（政府网站常见 GBK/GB2312）
      2. BeautifulSoup 剔除导航/页脚等语义噪声标签（nav/footer/header/aside/script/style）
      3. 将 h1-h4 转换为 # ## ### #### Markdown 标题，与 DocxParser 输出完全等价
      4. 其余 p/li/td/th 提取纯文本
    """

    def can_parse(self, file_path: str) -> bool:
        return file_path.lower().endswith((".html", ".htm"))

    def parse(self, file_path: str) -> str:
        """解析 HTML 文件为 Markdown 文本，失败时返回空字符串（触发 MarkItDown 兜底）"""
        try:
            import chardet as _chardet
            from bs4 import BeautifulSoup as _BS

            with open(file_path, "rb") as _hf:
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

            result = "\n".join(_hlines).strip()
            if result:
                print(f"✅ [HtmlParser] 解析成功 ({len(result)} 字符, enc={_henc})")
            return result
        except Exception as _err:
            print(f"⚠️ [HtmlParser] 解析失败: {_err}")
            return ""
