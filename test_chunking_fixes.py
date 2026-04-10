"""
分片质量审计修复验证测试
覆盖：C1 C2 C3 C5 H1 H2 H3 V1 CL1

运行：cd e:/project/AI/knowledge-base && python test_chunking_fixes.py
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'ai_service'))

import re
from collections import Counter
from core.chunking.hierarchy_detector import HierarchyDetector
from core.chunking.coarse_chunker import CoarseChunker
from core.chunking.fine_chunker import FineChunker
from core.cleaning.text_cleaner import TextCleaner

PASS = 0
FAIL = 0

def check(cond: bool, label: str, detail: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {label}")
    else:
        FAIL += 1
        print(f"  [FAIL] {label}")
        if detail:
            print(f"      >> {detail}")

# ─── C1：文号正则扩展 ───────────────────────────────────────────────────────
print("\n【C1】文号正则扩展")
cc = CoarseChunker({"max_chunk_size": 500, "min_chunk_size": 30,
                     "target_chunk_size": 350, "overlap_size": 0})
C1_cases = [
    ("静宁县政发〔2024〕15号",      True,  "标准全角括号格式"),
    ("（2024）静政决字第001号",     True,  "全角括号带第X号"),
    ("(2024)静政决字第001号",      True,  "半角括号带第X号"),
    ("静宁县政府令第38号",          True,  "命令格式"),
    ("公告第2024-001号",           True,  "公告编号格式"),
    ("2024年3月15日",               False, "日期行不应命中"),
    ("第15页",                      False, "页码行不应命中（极简第X号需排除页码）"),
]
for line, expect, label in C1_cases:
    got = bool(cc.RE_DOC_NUM.match(line))
    check(got == expect, f"C1 {label}", f"input='{line}' expect={expect} got={got}")

# ─── C3：但书条款独立切片 ──────────────────────────────────────────────────
print("\n【C3】但书条款独立切片")
fc = FineChunker({"max_chunk_size": 500})
legal_text = """第十五条 符合下列条件之一的，可以申请本市户籍登记：
（一）在本市连续合法居住满五年以上且无违法记录的；
（二）在本市依法就业满三年以上并缴纳社保的；
但有下列情形之一的，不得提出申请：
（一）有违法犯罪记录尚未解除的；
（二）有恶意欠税或者失信记录的。
"""
chunks = fc._scan_articles(legal_text)
paths = [c.section_path for c in chunks]
contents = [c.content for c in chunks]
has_dansh_chunk = any("但书" in p for p in paths)
has_positive_chunk = any("（一）" in p and "但书" not in p for p in paths)
check(has_dansh_chunk, "C3 但书行生成独立 chunk（path包含'但书'）",
      f"实际 paths={paths}")
check(has_positive_chunk, "C3 正向条款仍独立存在",
      f"实际 paths={paths}")

# ─── C5：一是/二是独立切片 ──────────────────────────────────────────────────
print("\n【C5】一是/二是独立切片")
report_text = """主要做了三项工作：一是加强组织领导，成立了工作专班，开展了5次专题研究，推动各项任务落实；
二是强化责任落实，明确分工，实现全覆盖；三是严格督导检查，形成闭环工作机制。
"""
fallback_chunks = fc._fallback_fine(report_text)
# 应该有三个 chunk，分别对应一是/二是/三是
texts = [c.content for c in fallback_chunks]
has_yishi_split = len(fallback_chunks) >= 3
check(has_yishi_split, f"C5 一是/二是/三是独立成chunk（期望≥3个，实际{len(fallback_chunks)}个）",
      f"实际 chunks={texts}")

# ─── H1：公文头部豁免区 ─────────────────────────────────────────────────────
print("\n【H1】公文头部豁免区")
header_doc = """静宁县人民政府
2024年3月15日
各乡镇人民政府、各局直单位：
现将《XX规划》印发给你们，请认真贯彻执行。

一、主要目标

到2025年底，实现XX目标。
"""
raw_chunks = cc._scan(header_doc)
# 称谓行不应该产生 section_path 带有称谓行内容的 chunk
# 检查：一旦 formal heading "一、" 出现，路径才应当包含章节
has_correct_path = any("一、" in c.section_path for c in raw_chunks)
# 称谓行"各乡镇…"不应在 section_path 里（它应在正文中）
caller_in_path = any("各乡镇" in c.section_path for c in raw_chunks)
check(has_correct_path, "H1 正式标题正确建立路径（一、主要目标）")
check(not caller_in_path, "H1 称谓行不被误识为标题",
      f"实际 paths={[c.section_path for c in raw_chunks]}")

# ─── H2：附件区编号豁免 ─────────────────────────────────────────────────────
print("\n【H2】附件区编号豁免")
annex_doc = """一、主要措施

加强管控，形成闭环。

附件：
1. 《规定》正文
2. 汇报材料
"""
raw2 = cc._scan(annex_doc)
# 附件区的 "1." 不应挂到 "一、/1." 路径下
annex_wrong_path = any("1." in c.section_path for c in raw2)
check(not annex_wrong_path, "H2 附件区'1.'不被识别为H3标题",
      f"实际 paths={[c.section_path for c in raw2]}")

# ─── H3：条文引用误触发防护 ─────────────────────────────────────────────────
print("\n【H3】条文引用误触发防护（RE_ARTICLE_CITE 行首引用词过滤）")
H3_cases = [
    ("第三条 本办法适用于本市行政区域内的活动。",  True,  "H3 正常条文行首为「第」应命中"),
    ("依据《管理办法》第三条，按以下规定执行：",    False, "H3 行首引用句：依据+《，不应命中"),
    ("根据《安全法》第五条规定，处以罚款。",        False, "H3 行首引用句：根据+《，不应命中"),
]
for line, expect, label in H3_cases:
    m_art = bool(fc.RE_ARTICLE.match(line))
    # 应用 RE_ARTICLE_CITE 过滤（与 _scan_articles 内逻辑一致）
    if m_art and fc.RE_ARTICLE_CITE.match(line):
        m_art = False
    check(m_art == expect, f"H3 {label}", f"input='{line}' expect={expect} got={m_art}")

# ─── V1：fine chunk 最小有效长度 ────────────────────────────────────────────
print("\n【V1】fine chunk 最小有效长度")
short_legal = """第三十条 禁止。
第三十一条 本办法自2024年1月1日起施行，原《办法》同时废止，请遵照执行。
"""
v1_chunks = fc._scan_articles(short_legal)
# "禁止。" 只有3字，不应生成 chunk
short_content_exists = any("禁止" in c.content and len(c.raw_content) < 20 for c in v1_chunks)
long_content_exists = any("施行" in c.content for c in v1_chunks)
check(not short_content_exists, "V1 极短条文（<20字）不生成 chunk")
check(long_content_exists, "V1 正常长度条文正常生成 chunk")

# ─── CL1：页眉页脚高频短行过滤 ──────────────────────────────────────────────
print("\n【CL1】页眉页脚高频短行过滤")
cleaner = TextCleaner()
# 模拟3页PDF，每页含同一页眉
page_text = (
    "静宁县人民政府文件\n"
    "关于印发规划的通知正文内容第一段。\n"
    "\n"
    "静宁县人民政府文件\n"
    "正文继续第二段内容，涉及具体措施安排。\n"
    "\n"
    "静宁县人民政府文件\n"
    "第三段内容，说明实施步骤和时间节点。\n"
)
cleaned = cleaner.clean(page_text)
header_in_result = "静宁县人民政府文件" in cleaned
check(not header_in_result, "CL1 高频页眉（≥3次）被过滤",
      f"过滤后仍含页眉: {header_in_result}")
normal_content_present = "正文" in cleaned
check(normal_content_present, "CL1 正常正文内容保留")

# ─── C2：公示人员边界检测（配置驱动验证）────────────────────────────────────
print("\n【C2】公示人员边界检测（配置驱动）")
from core.chunking.doc_type.strategies import PublicNoticeDocStrategy
strategy = PublicNoticeDocStrategy()
cfg = strategy.chunk_cfg.to_dict()
check("person_boundary_words" in cfg and cfg["person_boundary_words"],
      "C2 PublicNoticeDocStrategy 配置了 person_boundary_words",
      f"实际 cfg keys={list(cfg.keys())}")
# 验证 CoarseChunker 读取了该配置
cc_notice = CoarseChunker(cfg)
check(cc_notice._person_boundary_re is not None,
      "C2 CoarseChunker 成功编译 person_boundary_re")
# 验证边界行触发
notice_doc = """现公示以下候选人信息：

姓名：张三
出生年月：1985年3月
拟任职务：副局长
工作经历：曾任局长助理。

姓名：李四
出生年月：1990年5月
拟任职务：科长
"""
notice_raw = cc_notice._scan(notice_doc)
person_chunks = [c for c in notice_raw if c.chunk_type == "body"]
check(len(person_chunks) >= 2, f"C2 两人公示信息独立成chunk（期望≥2，实际{len(person_chunks)}）",
      f"实际body chunks={[c.content[:30] for c in person_chunks]}")

# ─── 汇总 ────────────────────────────────────────────────────────────────────
print(f"\n{'='*60}")
print(f"结果：{PASS} 通过 / {FAIL} 失败 / 共 {PASS+FAIL} 个")
if FAIL:
    print("⚠️  有用例失败，请检查对应修复逻辑！")
    sys.exit(1)
else:
    print("✅  全部通过！")
