import re
from typing import List, Optional

from core.chunking.models import Chunk
from core.chunking.hierarchy_detector import HierarchyDetector


class FineChunker:
    """
    业务功能：细粒度切片器（条文/条款/短句级，15-150字，精确语义定位路径）。
    从 SemanticChunker._process_fine + _fallback_fine_from_text 提取（架构重构阶段三）。

    两条输出路径：
      主路径：识别「第X条」和「（一）」型条款，生成 article 类型 chunk（quality=1.0）
      补偿路径（Fix3）：无任何条文时从正文拆分短句（15≤len≤80），确保所有文档均有精准召回
    """

    # [H3 修复] 条文识别正则（恢复为原始格式）
    # 引用句排除改用独立正则 RE_ARTICLE_CITE（见下方），在逐行处理时进行过滤。
    # 根因：在 RE_ARTICLE 中用 (?!.*?引用词.*?《) 负向前瞻会破坏所有条文匹配，
    #       因为 Python re 的负向前瞻无法可靠地扫描整行内容。
    # 正确策略：引用句特征是「行首为"依据/根据/按照"等动词」，
    #           而正常条文声明行首字是「第」，二者互斥，分开匹配即可。
    RE_ARTICLE = re.compile(
        r'^(第[一二三四五六七八九十百千零\d]+条)[　\s]?'
    )
    # [H3] 引用式行识别：行首以「依据/根据/按照/经」+ 内容 + 《法规名》
    # 命中此正则的行即为引用句，不应触发条文识别
    RE_ARTICLE_CITE = re.compile(
        r'^(依据|根据|按照|依照|经|鉴于|参照)[^，。]{0,30}《'
    )
    # [C3] 但书边界正则：「但有下列情形」「但符合下列条件」等反向例外条款的起始行
    # 根因：同一条文内的「但书」描述的是反向/例外规定，语义与正向条款完全相反，
    #       必须独立成 chunk，否则 RAG 回答「不得申请」类负向问题时精确率极低。
    RE_DANSH = re.compile(r'^但(?:有|符合|存在|属于|具有|出现).{0,20}[：:]')

    # 括号编号条款（扩展）：全角中文括号、全角阿拉伯数字括号、半角阿拉伯数字括号
    # [格式扩展] 新增 （1）（2）和 (1)(2) 半角括号格式（合同、询问笔录常用）
    RE_BULLET = re.compile(r'^(（[一二三四五六七八九十\d]+）|\(\d+\)|（\d+）)')
    # [P1-B 新增] 会议纪要决议块锁定词（触发标志）
    RE_DECISION_START = re.compile(r'(决定如下|议定事项|形成以下决议|决议如下|经研究决定)[：:]')
    # [P1-B 新增] 决议条目格式（中文序号/全角括号/数字序号）
    RE_DECISION_ITEM  = re.compile(r'^[一二三四五六七八九十\d][、．\.]|^（[一二三四五六七八九十\d]+）')
    # [缺陷5 新增] 询问笔录问答对识别触发词（前2000字出现「问：」定为问答式文书）
    RE_QA_START  = re.compile(r'^问[：:]', re.MULTILINE)
    RE_QA_ANSWER = re.compile(r'^答[：:]', re.MULTILINE)
    # [缺陷2 新增] 款级识别：「第X款」独立行，排除RE_ARTICLE已匹配的「第X条」格式
    RE_CLAUSE = re.compile(
        r'^(第[一二三四五六七八九十百千零\d]+款)[　\s]?'
    )
    # [C5] 一是/二是 散文式列举识别，用于 _fallback_fine 独立切片
    # 根因：工作报告「一是...；二是...；」每个分项以顿号逗号结尾，
    #       按句末标点切割无法区分各分项，导致多个分项被合并成 250字大 chunk。
    RE_YISHI = re.compile(r'^[一二三四五六七八九十]+是[，：:]?\s*(.+)')

    def __init__(self, cfg: dict):
        self.max_chunk_size = cfg.get("max_chunk_size", 500)
        self._detector = HierarchyDetector()

    def process(self, markdown_text: str) -> List[Chunk]:
        """
        业务功能：细粒度切片主入口，输出条文/条款 chunk 列表。
        关键流程：
          ① 决议块扫描（仅对含锚定词的文档触发，前3000字采样检测）
          ② 问答对扫描（仅对含「问：」的文书触发，前2000字采样检测）
          ③ 原有主路径：逐行扫描条文第X条 / （一）型条款
          ④ Fix3 补偿路径：无条文且无问答对时才启用短句切分
        """
        # ① 决议块优先扫描
        decision_chunks = []
        if self.RE_DECISION_START.search(markdown_text[:3000]):
            decision_chunks = self._scan_decisions(markdown_text)

        # ② 问答对扫描（询问/讯问笔录专项）
        qa_chunks = []
        if self.RE_QA_START.search(markdown_text[:2000]):
            qa_chunks = self._scan_qa_pairs(markdown_text)

        # ③ 条文/条款主路径
        article_chunks = self._scan_articles(markdown_text)

        # ④ 补偿路径：无条文且无问答对时才启用；有问答对时跳过，避免重复分片
        if not article_chunks and not qa_chunks:
            article_chunks = self._fallback_fine(markdown_text)

        return decision_chunks + qa_chunks + article_chunks


    # ── 主路径：条文/条款扫描 ────────────────────────────────────────────────

    def _scan_articles(self, markdown_text: str) -> List[Chunk]:
        """
        主路径：逐行扫描条文/条款，生成细粒度 chunk。

        [缺陷2 修复] 支持条→款→项 三层状态机。
        [C3 修复]   但书行触发 flush_bullet，将但书独立成 chunk。
        [H3 修复]   RE_ARTICLE 已加负向前瞻，引用式行不再触发条文识别。
        [V1 修复]   article chunk 最小有效长度提升到 20 字（BGE-M3 有效阈值）。

        状态机三层：
          条（RE_ARTICLE）→ 款（RE_CLAUSE, 可选）→ 项（RE_BULLET）
          section_path = "第X条" | "第X条/第Y款" | "第X条/第Y款/（Z）"
        """
        chunks: List[Chunk] = []
        lines = markdown_text.splitlines()
        # ── 条级状态 ──
        buf_article_id: Optional[str] = None
        buf_lines: List[str]           = []
        # ── 款级状态 ──
        buf_clause_id: Optional[str]   = None
        buf_clause_lines: List[str]    = []
        # ── 项级状态 ──
        current_bullet_id: Optional[str] = None
        current_bullet_lines: List[str]  = []
        parent_header: str = ""
        _table_header: list = []
        _in_table: bool = False

        def _build_path(article_id, clause_id, bullet_id) -> str:
            """三层路径拼建，只包含非空层级。"""
            parts = [p for p in [article_id, clause_id, bullet_id] if p]
            return '/'.join(parts)

        def flush_bullet():
            nonlocal current_bullet_id, current_bullet_lines
            if current_bullet_id and current_bullet_lines:
                raw = ' '.join(current_bullet_lines).strip()
                # [V1] article 类 chunk 最小有效长度提升到 20 字
                # 根因：BGE-M3 对 <20 字文本 embedding 质量极低（attention 层无足够上下文消歧）
                if len(raw) >= 20:
                    context = self._build_context(parent_header)
                    content = f"{context}{raw}"
                    if len(content) > self.max_chunk_size:
                        content = self._truncate_at_sentence(content)
                    path = _build_path(buf_article_id, buf_clause_id, current_bullet_id)
                    chunks.append(Chunk(
                        content=content,
                        raw_content=raw,
                        chunk_type="article",
                        section_path=path,
                        quality_score=1.0
                    ))
            current_bullet_id = None
            current_bullet_lines = []

        def flush_clause():
            """[缺陷2] flush 款级状态：款下没有括号「项」时，将款级正文单独 flush。"""
            nonlocal buf_clause_id, buf_clause_lines
            if buf_clause_id and buf_clause_lines:
                raw = ' '.join(buf_clause_lines).strip()
                if len(raw) >= 20:  # [V1]
                    path = _build_path(buf_article_id, buf_clause_id, None)
                    content = f"{path} {raw}" if path else raw
                    content = content if len(content) <= self.max_chunk_size else self._truncate_at_sentence(content)
                    chunks.append(Chunk(
                        content=content,
                        raw_content=raw,
                        chunk_type="article",
                        section_path=path,
                        quality_score=1.0
                    ))
            buf_clause_id = None
            buf_clause_lines = []

        def flush_article():
            nonlocal buf_article_id, buf_lines, parent_header
            if buf_article_id and buf_lines:
                raw = '\n'.join(buf_lines).strip()
                # [V1] 最小有效长度 20 字
                if len(raw) >= 20:
                    if len(raw) > self.max_chunk_size:
                        chunks.extend(self._split_long_article(raw, buf_article_id))
                    else:
                        content = f"[{buf_article_id}] {raw}"
                        chunks.append(Chunk(
                            content=content,
                            raw_content=raw,
                            chunk_type="article",
                            section_path=buf_article_id,
                            quality_score=1.0
                        ))
            buf_article_id = None
            buf_lines = []

        for line in lines:
            stripped = line.strip()
            if not stripped:
                _in_table = False
                _table_header = []
                continue

            m_art   = self.RE_ARTICLE.match(stripped)
            m_cla   = self.RE_CLAUSE.match(stripped)
            m_blt   = self.RE_BULLET.match(stripped)
            m_dansh = self.RE_DANSH.match(stripped)  # [C3] 但书边界

            # [H3] 引用句过滤：「依据《XX法》第X条规定」不是条文声明，是引用语境。
            # 此类行行首为引用动词，不是「第」，但行内仍含「第X条」，须在 m_art 之后覆盖。
            # 例：「依据《安全法》第三条，…」→ RE_ARTICLE 命中「第三条」，但 RE_ARTICLE_CITE
            #     也命中行首「依据」，因此将 m_art 置 None 跳过条文处理。
            if m_art and self.RE_ARTICLE_CITE.match(stripped):
                m_art = None


            # 表格行处理
            is_table_row = stripped.startswith('|') and stripped.endswith('|')
            is_separator = is_table_row and re.search(r'\|\s*-+\s*\|', stripped)
            if is_table_row and not is_separator:
                cells = [c.strip() for c in stripped.split('|') if c.strip()]
                if not cells:
                    continue
                if not _in_table:
                    _table_header = cells
                    _in_table = True
                    continue
                if len(' '.join(cells)) >= 8:
                    if _table_header and len(_table_header) == len(cells):
                        pairs = [f"{h}: {v}" for h, v in zip(_table_header, cells) if h and v]
                        raw = '，'.join(pairs)
                    else:
                        raw = '  '.join(cells)
                    _tbl_path = _build_path(buf_article_id, buf_clause_id, None)
                    chunks.append(Chunk(
                        content=raw, raw_content=raw,
                        chunk_type="table",
                        section_path=_tbl_path,
                        quality_score=0.9
                    ))
                continue
            elif _in_table:
                _in_table = False
                _table_header = []

            if m_art:
                # 遇到新「条」：从内层开始逐层 flush
                flush_bullet()
                flush_clause()
                flush_article()
                buf_article_id = m_art.group(1)
                parent_header  = stripped
                buf_lines      = [stripped]
                buf_clause_id  = None
                buf_clause_lines = []
                current_bullet_id = None
                current_bullet_lines = []

            elif m_dansh and buf_article_id:
                # [C3] 遇到但书行：flush 当前正向条款（bullet），记录但书头部作为语境层。
                # 但书后续的「（一）（二）」是具体的例外情形，path 需要包含"但书/"前缀，
                # 使但书例外条款与正向条款在 section_path 上物理隔离，避免检索混淆。
                # 根因：若不加"但书/"层，「（一）有违法记录」的 path 与正向「（一）」相同，
                #       RAG 召回时无法区分正向条件和反向例外。
                flush_bullet()
                # 设置但书标记，后续 m_blt 分支将据此在 path 中插入"但书/"前缀
                buf_article_id = buf_article_id + "/但书"   # 临时修改 article_id 以影响 path
                parent_header  = stripped   # 为后续 bullet 提供上下文（[不得申请] 前缀）
                buf_lines.append(stripped)

            elif m_cla and buf_article_id and not m_art:
                # [缺陷2] 遇到新「款」：必须在条层内部才生效
                flush_bullet()
                flush_clause()
                buf_clause_id    = m_cla.group(1)
                buf_clause_lines = [stripped]
                buf_lines.append(stripped)

            elif m_blt:
                flush_bullet()
                current_bullet_id    = m_blt.group(1)
                current_bullet_lines = [stripped]
                if buf_article_id:
                    buf_lines.append(stripped)
                if buf_clause_id:
                    buf_clause_lines.append(stripped)

            elif current_bullet_id:
                current_bullet_lines.append(stripped)
                if buf_article_id:
                    buf_lines.append(stripped)
                if buf_clause_id:
                    buf_clause_lines.append(stripped)
                if sum(len(l) for l in current_bullet_lines) > self.max_chunk_size:
                    flush_bullet()

            elif buf_clause_id:
                buf_clause_lines.append(stripped)
                buf_lines.append(stripped)
                if sum(len(l) for l in buf_clause_lines) > self.max_chunk_size:
                    flush_clause()

            elif buf_article_id:
                buf_lines.append(stripped)
                if sum(len(l) for l in buf_lines) > self.max_chunk_size:
                    flush_article()

        flush_bullet()
        flush_clause()
        flush_article()
        return chunks

    # ── [P1-B] 会议纪要专项：决议块锚定扫描 ──────────────────────────────────

    def _scan_decisions(self, markdown_text: str) -> List[Chunk]:
        """
        业务功能：识别会议纪要中"决定如下："后的决议列表，每个决议项单独成 fine chunk。
        关键设计：
          [会议决议] 前缀提升 BM25 检索权重，quality=1.0 与「第X条」同等优先级。
          section_path 以「决议/」为一级路径，天然与条文/段落路径不重叠，无需去重逻辑。
        """
        chunks:  List[Chunk]  = []
        in_dec:  bool         = False
        buf:     List[str]    = []
        item_id: Optional[str] = None

        def flush():
            nonlocal item_id, buf
            if item_id and buf:
                raw = ' '.join(buf).strip()
                if len(raw) >= 10:
                    chunks.append(Chunk(
                        content=f"[会议决议] {item_id} {raw}",
                        raw_content=raw,
                        chunk_type="article",
                        section_path=f"决议/{item_id}",
                        quality_score=1.0
                    ))
            item_id = None
            buf = []

        for line in markdown_text.splitlines():
            s = line.strip()
            if not s:
                continue
            if self.RE_DECISION_START.search(s):
                in_dec = True
                continue
            if in_dec:
                if self.RE_DECISION_ITEM.match(s):
                    flush()
                    item_id = s[:4]
                    buf     = [s]
                elif item_id:
                    buf.append(s)

        flush()
        return chunks

    # ── [缺陷5 新增] 询问/讯问笔录：问答对合并 ─────────────────────────────────

    def _scan_qa_pairs(self, markdown_text: str) -> List[Chunk]:
        """
        业务功能：识别询问笔录/讯问笔录中「问：…答：…」对话格式，
        将每对问答合并为单一 fine chunk，保证语义完整性。
        关键设计：
          - 以「问：」触发新的问答对积累，以下一个「问：」为结束边界并 flush 前一对
          - Q+A 合入同一 chunk：BM25 同时索引问题词和答案词
          - section_path = "问答/第N对"，天然与条文/段落路径不重叠
          - quality_score = 1.0，与「第X条」同等优先级
        """
        chunks: List[Chunk] = []
        buf_q: List[str] = []
        buf_a: List[str] = []
        qa_idx: int = 0
        in_answer: bool = False

        def flush_qa():
            nonlocal qa_idx, buf_q, buf_a, in_answer
            if not buf_q:
                return
            qa_idx += 1
            q_text = ' '.join(buf_q).strip()
            a_text = ' '.join(buf_a).strip()
            raw = (q_text + ' ' + a_text).strip() if a_text else q_text
            if len(raw) >= 8:
                chunks.append(Chunk(
                    content=f"[询问笔录] {raw}",
                    raw_content=raw,
                    chunk_type="article",
                    section_path=f"问答/第{qa_idx}对",
                    quality_score=1.0
                ))
            buf_q.clear()
            buf_a.clear()
            in_answer = False

        for line in markdown_text.splitlines():
            s = line.strip()
            if not s:
                continue
            if self.RE_QA_START.match(s):
                flush_qa()
                buf_q = [s]
                in_answer = False
            elif self.RE_QA_ANSWER.match(s) and buf_q:
                buf_a = [s]
                in_answer = True
            elif in_answer:
                buf_a.append(s)
            elif buf_q and not in_answer:
                buf_q.append(s)

        flush_qa()
        return chunks

    # ── 补偿路径（Fix3）：非条文类文档短句切分 ──────────────────────────────

    def _fallback_fine(self, markdown_text: str) -> List[Chunk]:
        """
        Fix3 补偿路径：段落感知的细粒度切分。

        核心改进（原有）：
          1. 段落感知：连续非空非标题行先合并为段落，避免逐行处理导致的语义割裂。
          2. 以真正的语义边界（句号 。！？；）切割主句，而非逐行暴力处理。
          3. 合并过短主句（< MIN_SENT 字）到下一句，保证语义完整性。
          4. 上限扩展至 MAX_SENT（250字），允许包含多个逗号分句的完整主句进入索引。

        [C5 修复] 新增「一是/二是」识别：
          工作报告「一是...；二是...；」每个分项以顿号/逗号/分号结尾，
          按句末标点切割无法区分各分项，导致多项被合并成超大 chunk。
          修复：阶段一识别 RE_YISHI 触发段落分割，每个「是X」作为独立段落。
        """
        MIN_SENT = 20
        MAX_SENT = 250

        fine_chunks: List[Chunk] = []
        current_path: List[str] = []

        RE_SENT_END = re.compile(r'(?<=[。！？；;])')
        RE_COMMA    = re.compile(r'(?<=[，,、])')

        # ── 阶段一：将连续非标题行合并为段落块（含 C5 一是/二是分割）────────
        paragraphs: List[tuple] = []  # (text, section_path)
        para_lines: List[str] = []
        para_path: str = ""

        # [C5] 「一是/二是」行内切割正则：对同一行含多个「X是」的情形，从内部拆分
        # 根因：「一是A；二是B；三是C」常常是一行或一段，逐行 RE_YISHI 只触发第一个「是X」，
        #       后续的「二是」「三是」在同一行里无法单独成行匹配。
        RE_YISHI_SPLIT = re.compile(r'(?=[一二三四五六七八九十]+是)', re.UNICODE)

        def flush_para():
            nonlocal para_lines, para_path
            if para_lines:
                text = ''.join(para_lines).strip()
                if text:
                    # [C5] 如果段落文本中含「X是」型分项，按「X是」内部切割
                    if self.RE_YISHI.search(text):
                        sub_parts = RE_YISHI_SPLIT.split(text)
                        for sp in sub_parts:
                            sp = sp.strip()
                            if sp and len(sp) >= 4:
                                paragraphs.append((sp, para_path))
                    else:
                        paragraphs.append((text, para_path))
                para_lines = []

        for line in markdown_text.splitlines():
            stripped = line.strip()
            if not stripped:
                flush_para()
                continue

            # [C5] 一是/二是行触发段落切分：
            # 遇到新的「X是」时，将当前段落先 flush，再把「X是...」作为新段落起点。
            # 这样每个「是X」独立成段，后续句末切割才能精确定位各分项。
            if self.RE_YISHI.match(stripped):
                flush_para()
                para_lines = [stripped]
                continue

            is_heading, new_path = self._detector.detect(stripped, current_path)
            if is_heading:
                flush_para()
                current_path = new_path
                para_path = "/".join(current_path)
                continue

            para_lines.append(stripped)

        flush_para()

        # ── 阶段二：按段落切分主句，合并过短尾句，拆分超长主句 ──────────────
        for (para_text, section_path) in paragraphs:
            parts = RE_SENT_END.split(para_text)
            parts = [p.strip() for p in parts if p.strip()]

            merged_parts: List[str] = []
            pending = ""
            for part in parts:
                if pending:
                    part = pending + part
                    pending = ""
                if len(part) < MIN_SENT:
                    pending = part
                else:
                    merged_parts.append(part)
            if pending:
                if merged_parts:
                    merged_parts[-1] += pending
                elif len(pending) >= MIN_SENT // 2:
                    merged_parts.append(pending)

            for sent in merged_parts:
                if len(sent) <= MAX_SENT:
                    fine_chunks.append(Chunk(
                        content=sent,
                        raw_content=sent,
                        chunk_type="article",
                        section_path=section_path,
                        quality_score=0.8
                    ))
                else:
                    sub_parts = RE_COMMA.split(sent)
                    buf = ""
                    for sp in sub_parts:
                        if len(buf) + len(sp) > MAX_SENT and buf:
                            if len(buf) >= MIN_SENT:
                                fine_chunks.append(Chunk(
                                    content=buf.strip(),
                                    raw_content=buf.strip(),
                                    chunk_type="article",
                                    section_path=section_path,
                                    quality_score=0.75
                                ))
                            buf = sp
                        else:
                            buf += sp
                    if buf.strip() and len(buf.strip()) >= MIN_SENT:
                        fine_chunks.append(Chunk(
                            content=buf.strip(),
                            raw_content=buf.strip(),
                            chunk_type="article",
                            section_path=section_path,
                            quality_score=0.75
                        ))

        return fine_chunks

    # ── 工具方法 ────────────────────────────────────────────────────────────

    def _build_context(self, parent_header: str) -> str:
        """根据 parent_header 关键词推断语义前缀（[设立条件]/[法律责任]/[职责权限]）"""
        if not parent_header:
            return ''
        ctx = parent_header.strip('：:').strip()
        if any(kw in ctx for kw in ['条件', '要求', '应当', '应具备', '资格', '符合']):
            return f'[设立条件] {ctx}：'
        if any(kw in ctx for kw in ['处罚', '罚款', '警告', '责令', '违反']):
            return f'[法律责任] {ctx}：'
        if any(kw in ctx for kw in ['职责', '权限', '权利', '义务', '负责']):
            return f'[职责权限] {ctx}：'
        return f'{ctx}：'

    def _split_long_article(self, raw: str, article_id: str) -> List[Chunk]:
        """
        业务功能：将超长条文拆分为多个带序号的子 chunk，替代原截断丢失策略。
        关键设计：
          - 按句末标点（。！？；）切分，保证每个子 chunk 语义完整
          - 每个子 chunk 携带「第X条（第N段）」标识，保持条文归属清晰
          - 单段即可满足长度时不加分段标识，保持原有格式
        """
        sents = re.split(r'(?<=[。！？；;])', raw)
        parts: List[str] = []
        buf = ""
        for s in sents:
            if not s.strip():
                continue
            if len(buf) + len(s) > self.max_chunk_size and buf:
                parts.append(buf.rstrip())
                buf = s
            else:
                buf += s
        if buf.strip():
            parts.append(buf.rstrip())

        if not parts:
            parts = [raw[:self.max_chunk_size]]

        result = []
        multi = len(parts) > 1
        for idx, part in enumerate(parts):
            if len(part) < 4:
                continue
            label = f"{article_id}（第{idx + 1}段）" if multi else article_id
            result.append(Chunk(
                content=f"[{label}] {part}",
                raw_content=part,
                chunk_type="article",
                section_path=article_id,
                quality_score=1.0
            ))
        return result or [Chunk(
            content=f"[{article_id}] {raw[:self.max_chunk_size]}",
            raw_content=raw[:self.max_chunk_size],
            chunk_type="article",
            section_path=article_id,
            quality_score=1.0
        )]

    def _truncate_at_sentence(self, content: str) -> str:
        """
        [P0-2 修复] 按句末标点智能截断，替代字符级硬截断。
        保留完整句子，截断点在 max_chunk_size 内最后一个句末标点后。
        """
        sents = re.split(r'(?<=[。！？])', content)
        buf = ""
        for s in sents:
            if len(buf) + len(s) > self.max_chunk_size:
                break
            buf += s
        return buf if buf else content[:self.max_chunk_size]
