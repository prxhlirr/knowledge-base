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
      规则CL1- 高频短行（页眉页脚）：全文出现 ≥3 次且行长 ≤30 字的相同行 → 过滤
               典型噪声：「静宁县人民政府文件」（每页页眉）、「第3页 共15页」（页码）、
               「机密★禁止复制」（密级标识）。这些行被 _merge_pass1 合入正文后，
               会污染语义向量，降低向量与正常提问的相似度。

    设计决策：
      - 正则在 __init__ 中预编译（原实现在函数内每次调用都重新编译，O(N)→O(1)）
      - 所有规则顺序执行，可通过继承或组合扩展
      - CL1 使用两轮扫描：第一轮统计频次、第二轮过滤，不引入额外正则
    """

    # [CL1] 页眉页脚判定阈值
    _HEADER_FOOTER_MIN_COUNT = 3   # 同一行出现 ≥3 次才判定为页眉页脚
    _HEADER_FOOTER_MAX_LEN   = 30  # 只对 ≤30 字的短行做高频过滤（正文段落不受影响）

    def __init__(self):
        # 预编译正则，避免在热路径（每次文档处理）中重复编译
        self._watermark_re = re.compile(r'([\u4e00-\u9fa5])\1{3,}')
        self._table_sep_re = re.compile(r'^\s*\|[\s\-\|]+\|\s*$')
        self._inline_repeat_re = re.compile(r'(.)\1{7,}')   # 规则5：任意字符连续>=8次
        self._effective_chars_re = re.compile(r'[^\u4e00-\u9fa5a-zA-Z0-9]')

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
        for line in lines:
            s = line.strip()
            if s and len(s) <= self._HEADER_FOOTER_MAX_LEN:
                short_line_counts[s] += 1
        # 高频短行集合（出现 ≥3 次）
        header_footer_lines: set = {
            s for s, cnt in short_line_counts.items()
            if cnt >= self._HEADER_FOOTER_MIN_COUNT
        }

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

            prev_line = stripped
            cleaned.append(line)

        result = '\n'.join(cleaned)

        # 规则6（内容门控）：过滤空文件和纯噪声文档
        # [Bug-10+12 修复] 底线降至 10 字：不阻挡正常单行简讯或极短测试文档
        effective_chars = self._effective_chars_re.sub('', result)
        if len(effective_chars) < 10:
            return ''

        return result
