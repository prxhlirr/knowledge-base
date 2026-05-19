import os
import re
from collections import Counter


class TextCleaner:
    """
    业务功能：文档文本进入切片器前的预净化门控。
    从 RAGPipeline._clean_raw_text 提取为独立可测试的清洗类（架构重构阶段一）。

    清洗规则：
      规则1  - 水印行：某汉字连续出现 ≥4 次（内内内内）→ 删除该行
      规则2  - 水印表格行：Markdown 表格行中所有单元格均为同一个汉字 → 删除
      规则3  - 高频重复行：连续 ≥3 行完全相同 → 折叠为 1 行
      规则4  - 乱码行：行内 Unicode 替换字符(U+FFFD)占比 >30% → 视为乱码删除
      规则5  - 行内重复字符：任意单字符连续出现 ≥8 次 → 盖章区装饰噪声，删除该行
      规则6  - 内容门控：净化后有效汉字/字母 < 10 字符 → 返回空串拒绝入库
      规则7  - 弧形盖章残片：连续多行仅 1~2 个汉字（OCR对圆形公章弧形排列的个字识别）→ 删除
                典型噪声：「中」「华」「人」「民」「共」「和」「国」「×」「」单字逐行（PP-Structure figure区域的 TextCleaner 兆底防线）
      规则8  - 底纹水印词：行内容属于预设水印词集合（"经密"/"保密"等）且行长极短 → 删除
                典型噪声：「内部文件」「绝密」「机密」「保密」「秘密」作为独立行出现（06规则底纹对正文词汇误伤期望低）
      规则CL1- 高频短行（页眉页脚）：全文出现 ≥3 次且行长 ≤30 字的相同行 → 过滤
                典型噪声：「静宁县人民政府文件」（每页页眉）、「第3页 兩15页」（页码）、
                「机密★禁止复制」（密级标识）。这些行被 _merge_pass1 合入正文后，
                会污染语义向量，降低向量与正常提问的相似度。

    设计决策：
      - 正则在 __init__ 中预编译（原实现在函数内每次调用都重新编译，O(N)→O(1)）
      - 所有规则顺序执行，可通过继承或组合扩展
      - CL1 使用两轮扫描：第一轮统计频次、第二轮过滤，不引入额外正则
      - 规则7（盖章单字）与规则8（水印词）为 Phase 2 扫描件专属规则，
        均不影响普通 PDF 路径（ぬ汉字单行 + 水印词精确匹配，误射率极低）
    """

    # [CL1] 页眉页脚判定阈值
    _HEADER_FOOTER_MIN_COUNT = 3   # 同一行出现 ≥3 次才判定为页眉页脚
    _HEADER_FOOTER_MAX_LEN   = 30  # 只对 ≤30 字的短行做高频过滤（正文段落不受影响）

    def __init__(self):
        # 预编译正则，避免在热路径（每次文档处理）中重复编译
        self._watermark_re         = re.compile(r'([\u4e00-\u9fa5])\1{3,}')
        self._table_sep_re         = re.compile(r'^\s*\|[\s\-\|]+\|\s*$')
        self._inline_repeat_re     = re.compile(r'(.)\1{7,}')   # 规则5：任意字符连续≥8次
        self._effective_chars_re   = re.compile(r'[^\u4e00-\u9fa5a-zA-Z0-9]')
        # 规则7：弧形盖章单字残片匹配式（全为中文汉字且长度 ≤2）
        self._seal_arc_re          = re.compile(r'^[\u4e00-\u9fa5×]{1,2}$')
        # 规则8：底纹水印词集合（frosen set O(1)查找，词元匹配误射率极低）
        # 按政务文件密级语义设计：这些词在官方文件正文中很少以独立行出现
        self._watermark_words      = frozenset([
            "内部文件", "内部资料", "内部使用", "仅供内部使用",
            "绝密", "机密", "秘密", "保密",
            "请勿外传", "严禁外传", "禁止复制",
        ])
        self._enable_cl1 = os.getenv("TEXT_CLEANER_ENABLE_CL1", "false").lower() == "true"

    def clean_line(self, text: str) -> str:
        """元素级轻量清洗；页眉页脚等结构噪声交给 NoiseClassifier。"""
        if not text:
            return text
        stripped = re.sub(r"[ \t]+", " ", text.strip())
        if not stripped:
            return ""
        if self._watermark_re.search(stripped):
            return ""
        replacement_count = stripped.count('\ufffd')
        if len(stripped) > 5 and replacement_count / len(stripped) > 0.30:
            return ""
        if self._inline_repeat_re.search(stripped):
            return ""
        if self._seal_arc_re.match(stripped):
            return ""
        if stripped in self._watermark_words and len(stripped) <= 10:
            return ""
        return stripped

    def clean(self, text: str) -> str:
        """
        业务功能：对原始提取文本执行全量净化，返回符合入库质量要求的净化文本。
        入参为空时直接返回原值；净化后有效字符 < 10 时返回空串（触发调用方的拒绝入库逻辑）。
        关键流程：
          ① 第一轮扫描：统计高频短行（页眉页脚候选词）
          ② 第二轮扫描：逐行应用五条过滤规则 + CL1 高频短行过滤
          ③ 内容门控检查
        """
        if not text:
            return text

        lines = text.splitlines()

        # ── [CL1] 第一轮扫描：统计全文短行频次 ────────────────────────────────
        # 根因：PDF/扫描件的页眉行（如「静宁县人民政府文件」）每页出现一次，
        #       在 pypdf/antiword 路径下会原样输出到纯文本，污染所有 chunk 的语义向量。
        # 策略：仅对 ≤30字 的短行计数，不扫描长行（避免误杀实质性短段落）。
        short_line_counts: Counter = Counter()
        if self._enable_cl1:
            for line in lines:
                s = line.strip()
                if s and len(s) <= self._HEADER_FOOTER_MAX_LEN:
                    short_line_counts[s] += 1
        # 高频短行集合（出现 ≥3 次）
        header_footer_lines: set = {
            s for s, cnt in short_line_counts.items()
            if cnt >= self._HEADER_FOOTER_MIN_COUNT
        } if self._enable_cl1 else set()

        # ── 第二轮扫描：逐行应用清洗规则 ───────────────────────────────────────
        cleaned: list = []
        prev_line: str = None
        repeat_count: int = 0

        for line in lines:
            stripped = line.strip()
            if not stripped:
                prev_line = stripped
                repeat_count = 0
                continue

            # [CL1] 页眉页脚高频短行过滤（优先级高于其他规则，直接跳过）
            if stripped in header_footer_lines:
                continue

            # 规则1：水印行（单汉字连续出现>=4次）
            if self._watermark_re.search(stripped):
                continue

            # 规则2：水印 Markdown 表格行（所有单元格均为同一汉字）
            if stripped.startswith('|') and stripped.endswith('|'):
                if self._table_sep_re.match(stripped):
                    continue
                cells = [c.strip() for c in stripped.split('|') if c.strip() and c.strip() != '---']
                if cells and all(len(c) == 1 and '\u4e00' <= c <= '\u9fa5' for c in cells):
                    if len(set(cells)) == 1:
                        continue

            # 规则3：高频重复行折叠（连续>=3行相同 -> 折叠为1行）
            if stripped == prev_line:
                repeat_count += 1
                if repeat_count >= 2:
                    continue
            else:
                repeat_count = 0

            # 规则4：乱码行（U+FFFD 替换字符占比 >30%）
            replacement_count = stripped.count('\ufffd')
            if len(stripped) > 5 and replacement_count / len(stripped) > 0.30:
                continue

            # 规则5：行内单字符连续重复 >=8 次（盖章区/印章边框噪声）
            # 覆盖场景：antiword 将印章边框/环形水印解析为"缓缓缓缓缓缓缓缓..."
            if self._inline_repeat_re.search(stripped):
                continue

            # [优化] 废弃原规则6（拉丁扩展密度）和规则7（有效字符密度）：
            # 这两条专门针对 antiword 产物，在 Gotenberg/LO 路径下会误杀正常内容
            # 由于当前已由 MarkItDown 和 Docx 正规结构化兜底解析，直接弃用此针对性危险规则

            # 规则7：弧形盖章单字残片（PP-Structure figure区域的 TextCleaner 兆底防线）
            # 场景：OCR 对圆形公章弧形排列识别出每行 1~2 个汉字（中||华|人||民|...）
            # 战略：行长 ≤2 且全为中文汉字（允许×：公章中有户中国增词），直接跳过
            # 注意：单个汉字行在普通文件正文中极少独立出现，误射率极低
            if self._seal_arc_re.match(stripped):
                continue

            # 规则8：底纹水印词駆魔（固定词集合，O(1)匹配）
            # 场景：PP-Structure text 区域将底纹水印（斤时汉字「内部文件」）误政为文字行
            # 战略：行内容属于预设词集 且 行长 ≤10 字 → 判定为水印过滤
            # 误射风险分析：「内部文件」在政务文件正文中很少以独立短行形式出现
            if stripped in self._watermark_words and len(stripped) <= 10:
                continue

            prev_line = stripped
            cleaned.append(line)

        result = '\n'.join(cleaned)

        # 规则6（内容门控）：过滤空文件和纯噪声文档
        # [Bug-10+12 修复] 底线降至 10 字：不阻挡正常单行简讯或极短测试文档
        effective_chars = self._effective_chars_re.sub('', result)
        if len(effective_chars) < 10:
            return ''

        return result
