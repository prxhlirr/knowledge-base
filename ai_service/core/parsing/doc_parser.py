import os
import re

from core.parsing.docx_parser import _parse_docx_to_markdown, _parse_docx_to_elements
from core.parsing.document_element import DocumentElement


class DocParser:
    """
    业务功能：解析旧版 .doc 文件（OLE格式，Word 97-2003）。
    关键流程（责任链，优先级从高到低）：
      1. 魔数检测：先读取文件前16字节，确定真实格式（ole/rtf/zip/html/unknown）
      2. RTF 路径：WPS/政府环境最常见，用 striprtf 解析
      3. ZIP 路径：.doc 实为 Open XML 格式，直接用 python-docx 解析
      4. HTML 路径：.doc 实为 HTML 格式，BeautifulSoup 解析
      5. OLE/unknown → G路(Gotenberg)→A路(antiword)→B路(python-docx)→C路(olefile)→D路(暴力)
    设计决策：
      - 每条路径失败后自动降级，不阻断任务队列
      - G路是首选（可保留标题层次），A路是常规降级，C/D路是最后保底
    """

    def can_parse(self, file_path: str) -> bool:
        return file_path.lower().endswith(".doc")

    def parse(self, file_path: str) -> str:
        """完整责任链：魔数检测 → RTF/ZIP/HTML/OLE四路分支，失败时返回错误描述字符串"""
        import chardet as _chardet

        # ── 魔数检测：确定 .doc 真实格式 ──────────────────────────────────────
        _doc_format = "unknown"
        try:
            with open(file_path, "rb") as _f:
                _magic = _f.read(16)
            if _magic[:4] == b"\xd0\xcf\x11\xe0":
                _doc_format = "ole"
            elif _magic[:5] == b"{\\rtf":
                _doc_format = "rtf"
            elif _magic[:2] == b"PK":
                _doc_format = "zip"
            elif b"<html" in _magic.lower() or b"<!doctype" in _magic.lower():
                _doc_format = "html"
            if _doc_format == "unknown":
                with open(file_path, "r", encoding="utf-8", errors="ignore") as _tf:
                    _head = _tf.read(20)
                if "{\\rtf" in _head or "{\\RTF" in _head:
                    _doc_format = "rtf"
        except Exception as _me:
            print(f"⚠️ [DocParser] 魔数检测失败: {_me}")
        print(f"[DocParser] .doc 真实格式: {_doc_format} ← {file_path}")

        # ── RTF 路径（WPS/政府环境最常见）────────────────────────────────────
        if _doc_format == "rtf":
            result = self._parse_rtf(file_path, _chardet)
            if result:
                return result

        # ── ZIP 路径（.doc 实为 Open XML）────────────────────────────────────
        elif _doc_format == "zip":
            result = self._parse_zip(file_path)
            if result:
                return result

        # ── HTML 路径（.doc 实为 HTML 格式）──────────────────────────────────
        elif _doc_format == "html":
            result = self._parse_html_doc(file_path, _chardet)
            if result:
                return result

        # ── OLE/unknown → G→A→B→C→D 责任链 ──────────────────────────────────
        if _doc_format in ("ole", "unknown"):
            result = self._parse_ole_chain(file_path, _chardet)
            if result:
                return result

        return f"【.doc 格式解析失败】：检测到真实格式为 {_doc_format}，所有解析路径均失败。请将文件另存为 .docx 或 .pdf 重新上传。"

    def parse_elements(self, file_path: str) -> list:
        """Best-effort structured parsing for .doc, with paragraph fallback."""
        try:
            with open(file_path, "rb") as f:
                magic = f.read(16)
            if magic[:2] == b"PK":
                import docx as _docx
                doc_obj = _docx.Document(file_path)
                return _parse_docx_to_elements(doc_obj, file_path)
        except Exception as err:
            print(f"⚠️ [DocParser] ZIP-doc 结构化解析失败，回退纯文本: {err}")

        text = self.parse(file_path)
        if not text:
            return []
        return [
            DocumentElement(text=line.strip(), type="paragraph", source="doc")
            for line in text.splitlines()
            if line.strip()
        ]

    # ── 子路径实现 ────────────────────────────────────────────────────────────

    def _parse_rtf(self, file_path: str, _chardet) -> str:
        """RTF 格式解析：优先 striprtf，失败则正则暴力剔除 RTF 控制字"""
        try:
            with open(file_path, "rb") as _f:
                _rtf_bytes = _f.read()
            _enc = _chardet.detect(_rtf_bytes[:2000]).get("encoding") or "utf-8"
            _rtf_content = _rtf_bytes.decode(_enc, errors="replace")
            try:
                from striprtf.striprtf import rtf_to_text
                _text = rtf_to_text(_rtf_content)
            except ImportError:
                _text = re.sub(r"\\[a-z]+\-?\d*\s?", " ", _rtf_content)
                _text = re.sub(r"[{}]", "", _text)
                _text = re.sub(r"\s{2,}", "\n", _text)
            if _text and _text.strip():
                print(f"[DocParser] RTF 解析成功 ({len(_text)} 字符, enc={_enc})")
                return _text.strip()
        except Exception as _err:
            print(f"⚠️ [DocParser] RTF 解析异常: {_err}")
        return ""

    def _parse_zip(self, file_path: str) -> str:
        """ZIP-doc 路径：.doc 实为 Open XML 格式，用 python-docx 解析。
        [修复] 原版只遍历 paragraphs，完全跳过表格，导致文号/签发人行丢失。
               改用 _parse_docx_to_markdown，段落+单行表格平铺+sdt 统一处理。
        """
        try:
            import docx as _docx
            from core.parsing.docx_parser import _parse_docx_to_markdown
            _doc_obj = _docx.Document(file_path)
            _text = _parse_docx_to_markdown(_doc_obj)
            if _text.strip():
                print(f"[DocParser] python-docx 解析 ZIP-doc 成功 ({len(_text)} 字节)")
                return _text
        except Exception as _err:
            print(f"⚠️ [DocParser] ZIP-doc 解析失败: {_err}")
        return ""

    def _parse_html_doc(self, file_path: str, _chardet) -> str:
        """HTML-doc 路径：.doc 实为 HTML 格式，BeautifulSoup 提取文本"""
        try:
            with open(file_path, "rb") as _hf:
                _hbytes = _hf.read()
            _enc = _chardet.detect(_hbytes[:2000]).get("encoding") or "utf-8"
            _html_content = _hbytes.decode(_enc, errors="replace")
            try:
                from bs4 import BeautifulSoup
                _text = BeautifulSoup(_html_content, "html.parser").get_text(separator="\n", strip=True)
            except ImportError:
                _text = re.sub(r"<[^>]+>", " ", _html_content)
                _text = re.sub(r"\s{2,}", "\n", _text).strip()
            if _text:
                return _text
        except Exception as _err:
            print(f"⚠️ [DocParser] HTML-doc 解析失败: {_err}")
        return ""

    def _parse_ole_chain(self, file_path: str, _chardet) -> str:
        """
        OLE/unknown 格式的完整降级责任链：
          G路(Gotenberg→docx) → A路(antiword) → B路(python-docx兼容) → C路(olefile) → D路(暴力)
        """
        # ── G路（首选）：Gotenberg 将 OLE/doc 转为 .docx，再用 python-docx 结构化提取 ──
        result = self._parse_g_path(file_path)
        if result:
            return result

        # ── A路（降级）：antiword 固定 UTF-8 输出 ────────────────────────────
        result = self._parse_a_path(file_path)
        if result:
            return result

        # ── B路：python-docx 兼容读取 ────────────────────────────────────────
        result = self._parse_b_path(file_path)
        if result:
            return result

        # ── C路：olefile WordDocument 流（全流 UTF-16LE 解码）────────────────
        result = self._parse_c_path(file_path)
        if result:
            return result

        # ── D路：暴力 chardet + UTF-16LE 提取 ────────────────────────────────
        return self._parse_d_path(file_path, _chardet)

    def _parse_g_path(self, file_path: str) -> str:
        """
        G路：调用 Gotenberg 将 OLE/doc 转为 .docx（保留标题层次），再用 python-docx 结构化提取。
        若 Gotenberg 返回 PDF（旧版不支持 docx 输出），自动降级为 pypdf 提取（无结构）。
        """
        import tempfile
        _p_text = ""
        try:
            import requests as _rq
            _gotenberg_url = os.getenv("GOTENBERG_URL", "http://gotenberg:3000")
            print(f"🔄 [G路] Gotenberg 转换（请求 docx 格式）: {os.path.basename(file_path)}")
            with open(file_path, "rb") as _f:
                _resp = _rq.post(
                    f"{_gotenberg_url}/forms/libreoffice/convert",
                    files={"files": (os.path.basename(file_path), _f)},
                    headers={"Gotenberg-Output-Filename": "output.docx"},
                    timeout=90
                )

            if _resp.status_code == 200:
                _resp_bytes = _resp.content
                _is_docx = _resp_bytes[:2] == b'PK'
                _is_pdf  = _resp_bytes[:4] == b'%PDF'

                if _is_docx:
                    # G路-优质分支：Gotenberg 返回了真正的 .docx
                    import docx as _docx_mod
                    with tempfile.NamedTemporaryFile(suffix=".docx", delete=False) as _t:
                        _t.write(_resp_bytes)
                        _tmp_docx_path = _t.name
                    try:
                        _g_doc = _docx_mod.Document(_tmp_docx_path)
                        _p_text = _parse_docx_to_markdown(_g_doc)
                        if _p_text:
                            _tbl_n = len(_g_doc.tables)
                            print(f"✅ [G路-优] Gotenberg→docx→python-docx 成功 "
                                  f"({len(_p_text)} 字符, {_tbl_n} 个表格, 位置已保留)")
                    finally:
                        os.remove(_tmp_docx_path)

                elif _is_pdf:
                    # G路-降级分支：Gotenberg 版本不支持 docx 输出，回退 PDF 提取
                    import io
                    from pypdf import PdfReader
                    print(f"⚠️ [G路-降] Gotenberg 返回 PDF（不支持 docx 输出），改用 pypdf 提取")
                    _reader = PdfReader(io.BytesIO(_resp_bytes))
                    _lines = [_pg.extract_text().strip() for _pg in _reader.pages if _pg.extract_text()]
                    _p_text = '\n'.join(_lines)
                    _eff = len(re.findall(r'[\u4e00-\u9fa5a-zA-Z0-9]', _p_text))
                    _density = _eff / max(len(_p_text), 1)
                    if _density < 0.10:
                        print(f"⚠️ [G路-降] PDF 有效密度极低 {_density:.0%}，放弃")
                        _p_text = ""
                    else:
                        print(f"  [G路-降] pypdf 提取: {len(_p_text)} 字符 (有效率={_density:.0%}, 注：无标题层次)")
                else:
                    print(f"⚠️ [G路] 响应格式未知（魔数: {_resp_bytes[:4].hex()}），放弃")
            else:
                print(f"⚠️ [G路] Gotenberg HTTP {_resp.status_code}: {_resp.text[:100]}")

        except Exception as _err:
            _gotenberg_url = os.getenv("GOTENBERG_URL", "http://gotenberg:3000")
            print(f"⚠️ [G路] 微服务通信异常 ({_gotenberg_url}): {_err}")

        return _p_text.strip()

    def _parse_a_path(self, file_path: str) -> str:
        """A路：antiword 固定 UTF-8 输出 + 爆炸比例门控(×2) + 有效密度二次验证"""
        try:
            import subprocess
            _aw_result = subprocess.run(
                ["antiword", "-m", "UTF-8.txt", file_path],
                capture_output=True, timeout=30
            )
            if _aw_result.returncode == 0 and _aw_result.stdout:
                _aw_text = _aw_result.stdout.decode("utf-8", errors="replace")

                # 爆炸比例校验（收紧至×2）
                _file_size = os.path.getsize(file_path)
                if len(_aw_text) > _file_size * 2:
                    print(f"⚠️ [A路] antiword 输出膨胀异常 "
                          f"({len(_aw_text)} chars vs {_file_size} bytes × 2)，判定图形层污染，放弃")
                    return ""

                # 有效密度二次验证
                if _aw_text.strip():
                    _eff_cnt = len(re.findall(r'[\u4e00-\u9fa5a-zA-Z0-9]', _aw_text))
                    if _eff_cnt / max(len(_aw_text), 1) < 0.20:
                        print(f"⚠️ [A路] antiword 有效字符密度不足，降级")
                        return ""

                if _aw_text.strip():
                    # 按行过滤：保留含有效汉字/英文/数字的行，丢弃纯噪声行
                    _aw_lines = [
                        _line.rstrip()
                        for _line in _aw_text.splitlines()
                        if re.search(r'[\u4e00-\u9fa5a-zA-Z0-9]', _line)
                    ]
                    _aw_text = '\n'.join(_aw_lines)

                if _aw_text.strip():
                    print(f"[A路] antiword 成功 ({len(_aw_text)} 字符)")
                    return _aw_text
            else:
                print(f"⚠️ [A路] antiword 错误: {_aw_result.stderr[:200]}")
        except FileNotFoundError:
            print("⚠️ [A路] antiword 未安装，尝试 olefile 降级")
        except Exception as _err:
            print(f"⚠️ [A路] antiword 异常: {_err}")
        return ""

    def _parse_b_path(self, file_path: str) -> str:
        """B路：python-docx 兼容读取（部分 .doc 实为 Open XML 格式）。
        [修复] 原版只遍历 paragraphs，跳过所有表格；改用 _parse_docx_to_markdown
               统一处理段落、单行表格平铺、sdt 提取，与 DocxParser 行为完全对齐。
        """
        try:
            import docx
            from core.parsing.docx_parser import _parse_docx_to_markdown
            doc_obj = docx.Document(file_path)
            text = _parse_docx_to_markdown(doc_obj)
            if text.strip():
                print(f"[B路] python-docx 兼容读取成功 ({len(text)} 字节)")
                return text
        except Exception as _err:
            print(f"⚠️ [B路] python-docx 无法读取: {_err}")
        return ""

    def _parse_c_path(self, file_path: str) -> str:
        """
        C路：olefile WordDocument 流全流 UTF-16LE 解码。
        修复：不使用固定偏移 0x80，对整个 WordDocument 流做全量解码，
        兼容 MS Word 和 WPS 格式。
        """
        try:
            import olefile as _ole
            if _ole.isOleFile(file_path):
                ole = _ole.OleFileIO(file_path)
                if ole.exists("WordDocument"):
                    raw = ole.openstream("WordDocument").read()
                    text_raw = raw.decode("utf-16-le", errors="replace")
                    readable = re.sub(r'[^\u4e00-\u9fa5\w\s，。！？、；：《》（）【】]', " ", text_raw)
                    readable = re.sub(r'\s{3,}', "\n", readable).strip()
                    ole.close()
                    _eff_cnt = len(re.findall(r'[\u4e00-\u9fa5a-zA-Z0-9]', readable))
                    _density = _eff_cnt / max(len(readable), 1)
                    if readable and len(readable) > 20 and _density >= 0.10:
                        print(f"[C路] olefile 全流提取成功: {len(readable)} 字符, 有效率={_density:.0%}")
                        return readable
                    elif readable:
                        print(f"⚠️ [C路] olefile 全流解码有效率过低 ({_density:.0%})，内容可能为乱码")
                else:
                    ole.close()
        except ImportError:
            pass
        except Exception as _err:
            print(f"⚠️ [C路] olefile 异常: {_err}")
        return ""

    def _parse_d_path(self, file_path: str, _chardet) -> str:
        """D路：chardet 检测编码 + 暴力提取可打印字符（最后保底）"""
        try:
            with open(file_path, "rb") as _bf:
                _raw_bytes = _bf.read()
            _enc = _chardet.detect(_raw_bytes[:4096]).get("encoding") or "utf-16-le"
            _raw_text = _raw_bytes.decode(_enc, errors="replace")
            readable = re.sub(r'[^\u4e00-\u9fa5\w\s，。]', " ", _raw_text)
            readable = re.sub(r'\s{3,}', "\n", readable).strip()
            if readable and len(readable) > 50:
                print(f"⚠️ [D路] 暴力提取 (enc={_enc}): {len(readable)} 字符")
                return readable
        except Exception as _err:
            print(f"⚠️ [D路] 暴力提取失败: {_err}")
        return ""
