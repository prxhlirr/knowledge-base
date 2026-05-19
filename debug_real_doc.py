"""在宿主机直接分析 测试1.docx 的真实 XML 结构"""
import sys, re
sys.path.insert(0, r"e:\project\AI\knowledge-base\ai_service")

import glob, os
import docx

# 找文件（中文文件名需要用 glob）
candidates = glob.glob(r"C:\Users\liyz\Desktop\**\*.docx", recursive=True)
path = None
for f in candidates:
    if os.path.getmtime(f) > 1745000000:  # 2026年的文件
        path = f
        break

if not path:
    # 直接用编码后的路径
    import ctypes
    path = r"C:\Users\liyz\Desktop\文档\测试1.docx"

print(f"分析文件: {path}")
doc = docx.Document(path)
ns = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'

print("\n=== body 直接子元素 ===")
for i, c in enumerate(doc.element.body):
    ltag = c.tag.split('}')[-1] if '}' in c.tag else c.tag
    print(f"  [{i:02d}] {ltag}")

print(f"\n=== txbxContent（浮动文本框）===")
txbxs = list(doc.element.body.iter(f'{{{ns}}}txbxContent'))
print(f"  共 {len(txbxs)} 个")
for i, t in enumerate(txbxs):
    txt = ''.join(x.text or '' for x in t.iter(f'{{{ns}}}t'))
    print(f"  [{i}]: {repr(txt[:200])}")

print("\n=== Word 页眉（Header）===")
for si, sec in enumerate(doc.sections):
    hdr = sec.header
    print(f"  section[{si}] is_linked={hdr.is_linked_to_previous}")
    for pi, p in enumerate(hdr.paragraphs):
        if p.text.strip():
            print(f"    para[{pi}]: {repr(p.text.strip())}")
    for ti, t in enumerate(hdr.tables):
        print(f"    table[{ti}]: {len(t.rows)} rows")
        for ri, r in enumerate(t.rows):
            cells = [c.text.strip() for c in r.cells]
            print(f"      row[{ri}]: {cells}")

print("\n=== _parse_docx_to_markdown 输出 ===")
from core.parsing.docx_parser import _parse_docx_to_markdown
result = _parse_docx_to_markdown(doc)
print(f"共 {len(result)} 字符")
print(repr(result[:600]))

print("\n=== 文号正则匹配 ===")
WEN_HAO = r"([A-Za-z\u4e00-\u9fa5]+(?:[（(][A-Za-z\u4e00-\u9fa5]+[)）])?[A-Za-z\u4e00-\u9fa5]{0,10}?\s*[〔\[(（【<]\s*[12]\d{3}\s*[〕\])）】>]\s*(?:第)?\s*\d{1,6}\s*号)"
m = re.search(WEN_HAO, result[:1500])
print(f"  {'[PASS] 命中: ' + repr(m.group(1)) if m else '[FAIL] 未命中'}")
