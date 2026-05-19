import re
from typing import List, Optional, Tuple


class HierarchyDetector:
    """
    业务功能：公文层级识别器，负责将行文本映射到标题层级和篇章路径。
    从 SemanticChunker 提取（架构重构阶段三），职责隔离：只负责识别，不负责切片逻辑。

    支持的标题格式（按优先级排列）：
      H0    - Markdown 标题（# ## ### ####），MarkItDown/python-docx 转换产物
      CHAPTER - 第X章/第X节/第X篇（规章制度、办法类文件）
      H1    - 一、二、三... 中文数字序号
      H2_FULL - （一）（二）全角括号（公文最常见格式）
      H2_HALF - (一)(二) 半角括号
      H3_DOT  - 1. 2. 点号型（负向预查排除日期/小数，P0-1修复）
      H3_DULL - 1、2、顿号（通知/公告最常见格式）
      YISHI   - 一是/二是... 散文式段序（工作汇报格式）
      INFER   - 隐式标题（纯文本路径专用，多信号联合判断，最低优先级）
    """

    # H0: Markdown 标题（MarkItDown 转换后常见）
    RE_H0 = re.compile(r'^#{1,4}\s+(.+)$')
    # [P1-A 新增] Markdown 加粗标题（Word 转换产物：python-docx 将 Heading 样式转为 **...**）
    # 示例: **一、总体要求** / **（一）主要目标** / **第一章 总则**
    # 限制：内容必须以常见公文标题式开头，防止正文中的加粗关键词被误识别
    RE_BOLD_H = re.compile(
        r'^\*\*('
        r'[一二三四五六七八九十]+[、。]'  # 一、二、 中文序号
        r'|（[一二三四五六七八九十\d]+）'  # （一）（二） 全角括号
        r'|第[\d一二三四五六七八九十百千零]+[章节篇条]'  # 第一章 第二节
        r'|\d+[\.、]'  # 1. 1、 数字序号
        r').+\*\*$'  # 内容至少 1 字
    )
    # H_CHAPTER: 第X章/第X节（规章制度、办法）
    RE_CHAPTER = re.compile(
        r'^(第[一二三四五六七八九十百千零\d]+[章节篇])[　\s]'
    )
    # H1: 一、二、三... 中文数序
    RE_H1 = re.compile(r'^[一二三四五六七八九十]+、(.*)$')
    # H2_FULL: （一）（二）全角括号（Fix1核心：几乎所有公文最常见格式）
    RE_H2_FULL = re.compile(r'^（[一二三四五六七八九十\d]+）(.*)$')
    # H2_HALF: (一)(二) 半角括号
    RE_H2_HALF = re.compile(r'^\([一二三四五六七八九十\u4e00-\u9fa5]+\)(.*)$')
    # H3_DOT: 1. 2. 点号型
    # [P0-1 修复] 添加 (?!\d) 负向预查，排除日期（2024.3.1）和小数（1.5亿）被误识别为标题
    RE_H3_DOT = re.compile(r'^\d+\.(?!\d)(.*)$')
    # H3_DULL: 1、2、顿号型（通知/公告最常见格式）
    RE_H3_DULL = re.compile(r'^\d+、(.*)$')
    # YISHI: 一是/二是... 散文式段序（工作汇报常见格式）
    RE_YISHI = re.compile(r'^[一二三四五六七八九十]+是[：:]?\s*(.*)$')

    # ── 隐式标题推断专用正则（_infer_naked_heading 使用）─────────────────────

    # 以句末标点结尾 → 完整陈述句 → 属于正文，拒绝推断为标题
    _RE_PREDICATE_END = re.compile(r'[。！？；]$')

    # 正文典型引导词前缀：此类词开头的行几乎必然是正文句，非标题
    # 根因：标题是名词短语，不以动词/副词开头
    # 扩展：增加"继续/深化/进一步"等行为延续动词（这类词起首的行是执行句，非标题短语）
    _RE_BODY_PREFIX = re.compile(
        r'^(为|根据|依据|按照|经|经过|鉴于|基于|现将|特此|请|要求|确保'
        r'|加强|推进|落实|完成|开展|组织|制定|建立|健全|坚持|积极|全面|深入'
        r'|继续|进一步|深化|持续|切实|严格|统筹|协调|规范|优化|提升|创新'
        r'|强化|完善|推动|实施|执行|贯彻)'
    )

    # 括号注释行（如「（详见附件）」「(以下简称XX)」）→ 正文注释，非标题
    _RE_PARENTHESIS_BODY = re.compile(r'^[（(].+[）)]$')

    # 纯数字/日期行（如「2024年3月」「第15页」）→ 非标题
    _RE_PURE_NUMBER = re.compile(r'^[\d\s年月日\-/：:一二三四五六七八九十第页共]+$')

    # 引导句尾缀：以"如下："/ "以下："结束的行是引导句而非标题
    # 注意：必须含冒号（区分"目标如下：" 与 "主要目标"）
    _RE_LEADING_SUFFIX = re.compile(r'(如下|以下|如下所述)[：:]?\s*$')

    # 公文隐式标题的正向词汇特征（以此类名词结尾则高度可能是标题）
    # 设计依据：政府公文的无序号标题几乎都是"形容词+名词"结构，以这些名词结尾
    # 注意：故意移除"工作"——太泛化（"推进相关工作" 等正文执行句末尾也含此词），
    #       保守策略：宁漏推断也不误判，这类标题行可由 CoarseChunker _merge_pass2 孤儿机制兜底。
    _RE_TITLE_SUFFIX = re.compile(
        r'(目标|任务|要求|措施|背景|原则|意义|现状|问题|方向|方案|规定|管理|建设|保障'
        r'|机制|说明|计划|部署|安排|步骤|流程|内容|情况|分析|概述|总结|展望'
        r'|结论|附则|定义|范围|职责|权限|处罚|奖励|监督|评估|验收|移交)$'
    )

    def detect(
        self,
        line: str,
        current_path: List[str],
        prev_blank: bool = False,
        next_blank: bool = False,
    ) -> Tuple[bool, List[str]]:
        """
        业务功能：检测当前行是否为标题，并计算新的篇章路径。

        关键流程：
          1. 优先按格式正则逐级命中（H0 → CHAPTER → H1 → H2 → H3 → YISHI）
          2. 全部格式正则未命中时，调用 _infer_naked_heading() 做多信号联合判断，
             识别纯文本路径（antiword/pypdf）中无序号格式的隐式标题行。

        参数：
          prev_blank: 当前行的前一行是否为空行（由 CoarseChunker._scan 传入）
          next_blank: 当前行的后一行是否为空行（由 CoarseChunker._scan 传入）

        返回：(is_heading: bool, new_path: List[str])
        注意：non-heading 时返回 (False, current_path) 保持路径不变。
        """
        stripped = line.strip()

        # [P1-A] 加粗标题（Word 转换产物，优先级高于 H0——它本质上就是 H0 的一种）
        # **一、总体要求** 、 **（一）主要目标** 、 **第一章 总则**
        if m := self.RE_BOLD_H.match(stripped):
            title = m.group(1).strip()  # 去除 ** 取内容
            # 一、二、型：H1 级，重置路径
            if re.match(r'[一二三四五六七八九十]+[、]', title):
                return True, [title.split('、')[0] + '、']
            # （一）型：H2 级，保留父节点
            elif re.match(r'（[一二三四五六七八九十\d]+）', title):
                head = re.match(r'（[一二三四五六七八九十\d]+）', title).group(0)
                parent = current_path[:1] if current_path else []
                return True, parent + [head]
            # 其他（第X章、数字序号）：作 H1 处理
            else:
                return True, [title]

        # H0: Markdown 标题（最高优先级，MarkItDown / python-docx 转换产物）
        # [P0-A 修复] 原代码对所有 # 层级均硬重置 current_path 为单元素列表，
        # 导致 ## / ### 标题的父章节路径丢失，面包屑断链。
        # 修复：按实际 # 数量维护层级深度，H1 才重置，H2/H3+ 向上保留父路径。
        if m := self.RE_H0.match(stripped):
            title = m.group(1)
            level = len(re.match(r'^(#+)', stripped).group(1))  # 1/2/3/4
            if level == 1:
                # H1：根节点，重置整棵路径（正确行为）
                return True, [title]
            elif level == 2:
                # H2：保留 H1 父节点
                parent = current_path[:1] if current_path else []
                return True, parent + [title]
            else:
                # H3+：保留前两层（章/节/条 三层已足够）
                parent = current_path[:2] if len(current_path) >= 2 else current_path[:1]
                return True, parent + [title]

        # H_CHAPTER: 第X章/节
        if self.RE_CHAPTER.match(stripped):
            chapter = self.RE_CHAPTER.match(stripped).group(1)
            return True, [chapter]

        # H1: 一、二、
        if self.RE_H1.match(stripped):
            return True, [stripped.split('、')[0] + '、']

        # H2_FULL: （一）全角括号
        if self.RE_H2_FULL.match(stripped):
            head = re.match(r'^（[一二三四五六七八九十\d]+）', stripped).group(0)
            if len(current_path) >= 1:
                return True, [current_path[0], head]
            return True, [head]

        # H2_HALF: (一) 半角括号
        if self.RE_H2_HALF.match(stripped):
            m = re.search(r'^\([一二三四五六七八九十\u4e00-\u9fa5]+\)', stripped)
            if m:
                head = m.group(0)
                if len(current_path) >= 1:
                    return True, [current_path[0], head]
                return True, [head]

        # H3_DOT: 1. 点号
        if self.RE_H3_DOT.match(stripped):
            m = re.search(r'^\d+\.', stripped)
            if m:
                head = m.group(0)
                if len(current_path) >= 2:
                    return True, current_path[:2] + [head]
                elif len(current_path) == 1:
                    return True, [current_path[0], head]
                return True, [head]

        # H3_DULL: 1、顿号
        if self.RE_H3_DULL.match(stripped):
            m = re.search(r'^\d+、', stripped)
            if m:
                head = m.group(0)
                if len(current_path) >= 2:
                    return True, current_path[:2] + [head]
                elif len(current_path) == 1:
                    return True, [current_path[0], head]
                return True, [head]

        # 一是/二是（散文式段序，作为 H2 级别处理）
        if self.RE_YISHI.match(stripped):
            head = stripped[:3]  # "一是"/"二是"
            if len(current_path) >= 1:
                return True, [current_path[0], head]
            return True, [head]

        # ── 兜底：隐式标题推断（纯文本路径专用，优先级最低）──────────────────
        # [D1 根治] 替代原审计报告中"行长≤20 + 前后空行 + 不以句号结尾"的单信号方案。
        # 根因：单信号方案误判率高（正文中"情况如下："完全满足条件），
        #       且在 TextCleaner 层注入 ## 前缀越俎代庖，违反职责边界。
        # 正确位置：HierarchyDetector 本就负责标题识别，多信号联合判断才是正解。
        inferred, inferred_path = self._infer_naked_heading(
            stripped, current_path, prev_blank, next_blank
        )
        if inferred:
            return True, inferred_path

        return False, current_path

    def _infer_naked_heading(
        self,
        stripped: str,
        current_path: List[str],
        prev_blank: bool,
        next_blank: bool,
    ) -> Tuple[bool, List[str]]:
        """
        业务功能：对纯文本路径（antiword/pypdf）中无任何序号格式的隐式标题行进行多信号联合识别。

        判断逻辑（AND 关系，全部满足才推断为标题，最后一项置信度判断决定是否输出）：
          [结构信号]   前后至少有一个空行（段落边界）
          [长度信号]   行长 2 ≤ N ≤ 20 字
          [排除信号-1] 不以句末标点结尾（。！？；）→ 否则是完整陈述句
          [排除信号-2] 不以正文引导词前缀开头（为/根据/依据...）
          [排除信号-3] 不是括号注释行、纯数字行
          [排除信号-4] 不是引导句（以"如下："/"以下："结尾）
          [置信度判断] 以常见政务标题名词结尾 OR 行长 ≤ 6 的纯名词短语

        为何要求"置信度判断"而不仅靠排除信号：
          存在大量短行满足前四条但不是标题，如"继续推进"（4字，正文小结句）。
          正向词汇特征是区分"标题名词短语"与"正文短句"的核心依据。

        参数：
          prev_blank / next_blank 由 CoarseChunker._scan() 传入，
          避免 HierarchyDetector 自身维护行迭代状态（职责隔离）。
        """
        # [结构信号] 前后至少有一个段落边界（空行）
        # 根因：正文中间的短句通常两边都是正文行，无空行间隔
        if not (prev_blank or next_blank):
            return False, current_path

        # [长度信号] 标题通常是短名词短语（2~20字），超过则几乎必然是正文句
        if len(stripped) > 20 or len(stripped) < 2:
            return False, current_path

        # [排除信号-1] 以句末标点结尾 → 完整句子 → 正文
        if self._RE_PREDICATE_END.search(stripped):
            return False, current_path

        # [排除信号-2] 正文引导词前缀开头（动词/副词起首的行是陈述句，非标题短语）
        if self._RE_BODY_PREFIX.match(stripped):
            return False, current_path

        # [排除信号-3] 括号注释行 / 纯数字行
        if self._RE_PARENTHESIS_BODY.match(stripped) or self._RE_PURE_NUMBER.match(stripped):
            return False, current_path

        # [排除信号-4] 引导句（"情况如下："等末尾含冒号的收尾词）
        if self._RE_LEADING_SUFFIX.search(stripped):
            return False, current_path

        # [置信度判断] 正向词汇特征：以常见政务标题名词结尾
        has_title_suffix = bool(self._RE_TITLE_SUFFIX.search(stripped))

        # 极短纯名词短语（≤6字）也接受，如"背景说明"(4字)、"总体要求"(4字)
        # 但必须不含任何动词时态标记（否则是正文短句）
        # 动词时态标记：了/着/过（动态助词）或 不/未/已（否定副词+动词）
        _has_verb_marker = bool(re.search(r'[了着过]|[不未已][^\u4e00-\u9fa5]{0,1}[^\u4e00-\u9fa5]?[\u4e00-\u9fa5]', stripped))
        is_short_noun_phrase = len(stripped) <= 6 and not _has_verb_marker

        if not (has_title_suffix or is_short_noun_phrase):
            return False, current_path

        # ── 所有信号通过，推断为隐式标题 ────────────────────────────────────
        # 统一作为 H1 级别（保守处理，无法在无序号格式时准确判断层级深度）
        title = stripped
        print(f"  [TitleInfer] 推断隐式标题: '{title}' "
              f"(prev_blank={prev_blank}, next_blank={next_blank}, "
              f"suffix={has_title_suffix}, short={is_short_noun_phrase})")
        return True, [title]
