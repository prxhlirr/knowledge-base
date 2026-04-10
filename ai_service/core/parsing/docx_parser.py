import os


def _docx_unique_cells(row):
    """
    合并单元格去重辅助函数：同一 _tc XML 节点只输出一次，消除重复列。
    根因：row.cells 对合并区域每个逻辑格均返回主格内容，产生重复列数据。
    """
    _seen, _cells = set(), []
    for _c in row.cells:
        _cid = id(_c._tc)
        if _cid not in _seen:
            _seen.add(_cid)
            _cells.append(_c.text.strip().replace('\n', ' '))
    return _cells


def _parse_docx_to_markdown(doc_obj) -> str:
    """
    业务功能：将 python-docx Document 对象转换为 Markdown 格式字符串。
    关键流程：按 XML body 元素顺序遍历（保留表格在段落中的原始位置，修复 BUG-04）。
    设计决策：提取为独立函数，供 DocxParser 和 DocParser(G路) 复用。
    """
    from docx.text.paragraph import Paragraph as _Para
    from docx.table import Table as _Table

    _lines = []
    for _child in doc_obj.element.body:
        _ltag = _child.tag.split('}')[-1] if '}' in _child.tag else _child.tag
        if _ltag == 'p':
            _para = _Para(_child, doc_obj)
            _ptxt = _para.text.strip()
            if not _ptxt:
                continue
            _sname = (_para.style.name if _para.style else '').lower()
            if   'heading 1' in _sname or '标题 1' in _sname: _lines.append(f'# {_ptxt}')
            elif 'heading 2' in _sname or '标题 2' in _sname: _lines.append(f'## {_ptxt}')
            elif 'heading 3' in _sname or '标题 3' in _sname: _lines.append(f'### {_ptxt}')
            elif 'heading 4' in _sname or '标题 4' in _sname: _lines.append(f'#### {_ptxt}')
            else: _lines.append(_ptxt)
        elif _ltag == 'tbl':
            _tbl = _Table(_child, doc_obj)
            if not _tbl.rows:
                continue
            _lines.append('')
            _th = _docx_unique_cells(_tbl.rows[0])
            if _th:
                _lines.append('| ' + ' | '.join(_th) + ' |')
                _lines.append('| ' + ' | '.join(['---'] * len(_th)) + ' |')
            for _tr in _tbl.rows[1:]:
                _tc = _docx_unique_cells(_tr)
                if any(_tc):
                    _lines.append('| ' + ' | '.join(_tc) + ' |')
            _lines.append('')

    return '\n'.join(_lines).strip()


class DocxParser:
    """
    业务功能：解析标准 .docx 文件，通过 python-docx 的 Heading 样式识别实现结构感知解析。
    核心价值：MarkItDown 无法识别 Word 原生 Heading 1/2/3 样式，所有段落被平铺为普通文本，
              导致 SemanticChunker 无法识别标题层次，切片质量大幅下降。
              python-docx 通过 paragraph.style.name 精准识别，输出真正的 # ## ### Markdown 标题。
    """

    def can_parse(self, file_path: str) -> bool:
        return file_path.lower().endswith(".docx")

    def parse(self, file_path: str) -> str:
        """
        解析 .docx 文件，按 XML body 顺序遍历段落和表格，输出 Markdown 格式文本。
        失败时返回空字符串，由 ParserFactory 降级至 MarkItDownParser。
        """
        try:
            import docx as _docx_mod
            _doc_obj = _docx_mod.Document(file_path)
            result = _parse_docx_to_markdown(_doc_obj)
            if result:
                _tbl_cnt = len(_doc_obj.tables)
                print(f'✅ [DocxParser] 结构化解析成功 ({len(result)} 字符, 含 {_tbl_cnt} 个表格, 位置已保留)')
                return result
            print('⚠️ [DocxParser] 提取内容为空，降级 MarkItDown')
            return ""
        except Exception as _err:
            print(f'⚠️ [DocxParser] 解析失败，降级 MarkItDown: {_err}')
            return ""
