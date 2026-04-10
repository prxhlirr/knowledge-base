import re
from typing import List

from core.chunking.models import Chunk
from core.chunking.hierarchy_detector import HierarchyDetector
from core.chunking.table_processor import TableProcessor


class CoarseChunker:
    """
    业务功能：粗粒度切片器（段落/章节级，100-500字，宽语义召回路径）。
    从 SemanticChunker._process_coarse 提取（架构重构阶段三）。

    三阶段处理流程：
      阶段一：行扫描 + 树状态机（原子化隔离：表格行/文号行/标题行/正文行各入独立 raw_chunk）
      阶段二：合并与分遣（小块合并、长块按句分割、保留 section_path 一致性）
      阶段三：面包屑织入、交叉重叠注入（跨章节 overlap 抑制）、质量过滤
    """

    # [C1 修复] 文号正则，覆盖政府公文文号的全部常见格式：
    #   标准格式：政发〔2024〕15号
    #   半角括号：（2024）静政决字第001号
    #   命令格式：静宁县政府令第38号
    #   公告编号：公告第2024-001号
    #   极简格式：第X号（部分内部文件）
    # 根因：原正则只覆盖"〔YYYY〕N号"，漏掉其他格式导致文号行当正文处理，
    #       文号与正文分离后检索文号只能找到无内容的孤儿 chunk。
    RE_DOC_NUM = re.compile(
        r'.*('
        r'〔\d{4}〕\d+号'               # 标准：政发〔2024〕15号
        r'|\(\d{4}\).{1,10}第\d+号'     # 半角括号：(2024)静政决字第001号
        r'|（\d{4}）.{1,10}第\d+号'    # 全角括号：（2024）静政决字第001号
        r'|令第\d+号'                   # 命令：XX令第38号
        r'|公告第\d{4}[-–]\d+号'        # 公告编号：公告第2024-001号
        r'|(?<![年月日期])第\d{1,4}号'  # 极简：第X号（排除日期序数）
        r').*'
    )
    RE_SENTENCE_END = re.compile(r'[。！？\n]')
    RE_PUNCTUATION  = re.compile(r'[^\w\s\u4e00-\u9fa5]')
    # [P0-3 修复] 条文边界检测，用于跨条文 overlap 隔离
    RE_ARTICLE_MARK = re.compile(r'第[一二三四五六七八九十百千零\d]+条')

    def __init__(self, cfg: dict):
        self.max_chunk_size    = cfg.get("max_chunk_size", 500)
        self.min_chunk_size    = cfg.get("min_chunk_size", 100)
        self.target_chunk_size = cfg.get("target_chunk_size", 350)
        self.overlap_size      = cfg.get("overlap_size", 50)
        self.min_quality_score = float(cfg.get("min_quality_score", 0.3))
        self._detector         = HierarchyDetector()
        self._table_proc       = TableProcessor()
        # [C2] 人员边界正则：由 PublicNoticeDocStrategy 通过 person_boundary_words 注入。
        # 非公示类文档此值为 None，_scan() 中零开销 skip。
        # 根因：公示块每行 20-30 字 < min_chunk_size，_merge_pass1 会把多人信息合并
        # 为一个 chunk，不同人员信息混入同一向量，违反信息原子性和隐私隔离原则。
        _words = cfg.get("person_boundary_words")
        self._person_boundary_re = (
            re.compile(r'^(' + '|'.join(re.escape(w) for w in _words) + r')')
            if _words else None
        )

    def process(self, markdown_text: str) -> List[Chunk]:
        """
        业务功能：将 Markdown 文本切分为粗粒度 chunk 列表。
        关键流程：行扫描(阶段一) → 合并分遣(阶段二) → 语境织入+质量过滤(阶段三)
        """
        raw_chunks = self._scan(markdown_text)
        merged     = self._merge(raw_chunks)
        return      self._enrich(merged)

    # ── 阶段一：行扫描，原子化隔离 ───────────────────────────────────────────

    def _is_table_row(self, line: str) -> bool:
        return line.strip().startswith('|') and line.strip().endswith('|')

    def _scan(self, markdown_text: str) -> List[Chunk]:
        """逐行扫描，标题/文号/表格/正文分别进入独立 raw_chunk

        [P0-1 修复] 文号行（doc_num）不再单独入库，改为暂存至 pending_doc_num。
        当下一个 body chunk 出现时，将文号注入为其首行语境前缀。
        根因：文号行单独成 chunk 后，正文 chunk 没有文号词，用户搜索
              「XX政发〔2024〕15号」时 BM25 命中文号 chunk（无正文），
              而包含实际内容的 body chunk 因无文号关键词而排名靠后。
        修复后：文号 + 正文合并到同一 chunk，同时被文号检索和内容检索命中。
        """
        lines = markdown_text.splitlines()
        raw_chunks: List[Chunk] = []
        current_path: List[str] = []
        current_body = []
        current_table = []
        # [P0-1] 文号暂存缓冲：等待注入到下一个 body chunk 开头
        pending_doc_num: str = ""
        # [P1-C] 表格前最后一行正文作为表格说明前缀（列表容器允许闭包内修改）
        _table_caption: List[str] = [""]

        # [H1] 公文头部豁免区：文档前段（发文机关/文号/日期/称谓行）不触发标题识别
        # 一旦出现第一个强格式标题（一、/第X章/# 等），head zone 结束。
        # 根因：公文"（一）"型称谓行（如"（二）推进...单位："）若在正文有格式，
        #       会被 RE_H2_HALF/RE_H2_FULL 误识别为 H2 节标题。
        _header_zone_passed: bool = False
        # 日期行正则：纯"YYYY年M月D日"型 —— 必然是公文日期，非标题
        _RE_HEADER_DATE = re.compile(r'^\d{4}年\d{1,2}月\d{1,2}日$')
        # 称谓行正则："各XXX："/指XXX：型行首 —— 公文主送机关，非标题
        _RE_HEADER_SALUTATION = re.compile(r'^(各|指)[^，。]{0,30}[：:]\s*$')
        # 强格式标题正则（命中此行即运出头部区）
        _RE_FORMAL_HEADING = re.compile(
            r'^(#{1,4}\s'                             # Markdown 一级到四级标题
            r'|第[\u4e00-\u9fa5\d]+[章节篇]'          # 第X章/节/篇
            r'|[一二三四五六七八九十]+、)'              # 一、二、中文数字序号
        )

        # [H2] 附件区豁免：检测到"附件："/"附录："后，该区域内 H3_DOT/H3_DULL 不触发标题
        # 根因：附件清单的"1.""2."是列表序号而非章节标题，误识别会导致 section_path 错乱
        _in_annex_zone: bool = False
        _RE_ANNEX_START = re.compile(r'^(附件|附录)[一二三四五六七八九十\d]*[：:\s]')


        def push_body():
            nonlocal pending_doc_num
            if current_body:
                text = "\n".join(current_body).strip()
                if text:
                    # [P0-1] 若有待注入的文号，拼接为首行语境前缀
                    if pending_doc_num:
                        text = pending_doc_num + "\n" + text
                        pending_doc_num = ""
                    raw_chunks.append(Chunk(
                        content=text,
                        chunk_type="body",
                        section_path="/".join(current_path)
                    ))
                current_body.clear()

        def push_table():
            if current_table:
                flat_texts = self._table_proc.flatten(current_table)
                for idx, flat_text in enumerate(flat_texts):
                    # [P1-C] 第一个表格 chunk 注入表格前的最后一行正文作为说明前缀。
                    # 根因：表格前的说明文字（如"2024年各区县工作完成情况如下："）被 push_body
                    # 单独 flush，表格 chunk 面包屑仅有章节路径而无说明，
                    # 搜索时表格数据缺乏关键上下文。
                    if idx == 0 and _table_caption[0]:
                        flat_text = f"[表格说明: {_table_caption[0]}]\n{flat_text}"
                    raw_chunks.append(Chunk(
                        content=flat_text,
                        chunk_type="table",
                        section_path="/".join(current_path)
                    ))
                current_table.clear()
                _table_caption[0] = ""  # 重置说明缓冲

        is_in_table = False

        # [D1 修复] 改为索引式遍历，以便向 detect() 传递前后行的空行上下文。
        # 根因：_infer_naked_heading() 需要知道"前一行是否为空行 + 后一行是否为空行"，
        # 才能使用结构信号判断该行是否为段落边界处的隐式标题。
        # 原 for-in 迭代无法回看/前看，必须改为索引式遍历。
        for idx, line in enumerate(lines):
            # [P0-A 修复] 空行作为段落边界触发 push_body()，不再直接 continue 跳过。
            if not line.strip():
                push_body()
                continue

            if self._is_table_row(line):
                is_in_table = True
                # [P1-C] 进入表格前，记录当前 body 的最后一行作为表格说明
                _table_caption[0] = current_body[-1].strip() if current_body else ""
                push_body()
                current_table.append(line)
                continue
            else:
                if is_in_table:
                    push_table()
                    is_in_table = False

            # [P0-1] 文号行暂存（不再单独入库）
            if self.RE_DOC_NUM.match(line):
                push_body()
                pending_doc_num = line.strip()  # 暂存，等待下一个 body
                continue

            # [C2] 人员边界检测（仅公示类文档启用，其他文档 _person_boundary_re 为 None）
            # 检测到"姓名："/"拟任："等人员起始信号时，强制 flush 当前段落，
            # 保证每位被公示人员的信息独立成一个 chunk，实现隐私隔离。
            if self._person_boundary_re and self._person_boundary_re.match(line.strip()):
                push_body()
                # 不 continue：该行是人员信息的第一行，需要进入正文缓存

            # 计算前后空行上下文，传递给 _infer_naked_heading() 的结构信号判断
            # prev_blank: 前一行是空行（或已是文档首行，视为逻辑边界）
            # next_blank: 后一行是空行（或已是文档末行，视为逻辑边界）
            prev_blank = (idx == 0) or (not lines[idx - 1].strip())
            next_blank = (idx == len(lines) - 1) or (not lines[idx + 1].strip())

            stripped = line.strip()

            # [H1] 头部区豁免：文档正式标题出现之前，称谓行/日期行不触发标题识别
            if not _header_zone_passed:
                if _RE_FORMAL_HEADING.match(stripped):
                    _header_zone_passed = True   # 进入正式文档区，后续正常识别
                elif _RE_HEADER_DATE.match(stripped) or _RE_HEADER_SALUTATION.match(stripped):
                    # 头部区的日期行/称谓行：直接作为正文行，跳过标题检测
                    current_body.append(line)
                    continue

            # [H2] 附件区豁免：检测到附件边界，激活附件区标志
            if _RE_ANNEX_START.match(stripped):
                _in_annex_zone = True

            is_heading, new_path = self._detector.detect(
                line, current_path, prev_blank=prev_blank, next_blank=next_blank
            )

            # [H2] 附件区内 H3 序号（"1.""1、"型）回退为正文行
            # 判断依据：new_path 比 current_path 深一层，且当前在附件区
            # 根因：附件清单的"1.""2."是序号，不是章节标题，误识别后
            #       section_path 变为 "一、/1."，把附件内容挂到了正文章节下
            if is_heading and _in_annex_zone and new_path and len(new_path) > len(current_path):
                is_heading = False

            if is_heading:
                # [标题孤儿修复] 标题行只更新层级路径，不强制 flush，
                # 继续追加到 current_body，成为下一段正文的开头行。
                push_body()          # 先把前一章节的正文 flush 出去
                current_path = new_path
                current_body.append(stripped)   # 标题行作为新段落的【首行】，等待正文跟进
            else:
                current_body.append(line)


        push_body()
        push_table()
        # [P0-1 兜底] 文号行位于文档末尾（后无正文）时，pending_doc_num 未被消费。
        if pending_doc_num:
            raw_chunks.append(Chunk(content=pending_doc_num, chunk_type="doc_num"))
        return raw_chunks

    # ── 阶段二：合并与分遣 ───────────────────────────────────────────────────

    def _merge(self, raw_chunks: List[Chunk]) -> List[Chunk]:
        """
        两阶段合并，彻底解决标题孤儿问题：
          Pass1：向后（backward）合并 —— 正文小块找前一块合并，长块按句分割（原有逻辑）
          Pass2：向前（forward）合并  —— 孤儿标题块前置到下一块（根源修复）
        两阶段职责独立，互不干扰，不依赖 section_path 格式。
        """
        pass1 = self._merge_pass1(raw_chunks)
        return self._merge_pass2(pass1)

    def _merge_pass1(self, raw_chunks: List[Chunk]) -> List[Chunk]:
        """
        Pass1: 向后合并 —— 正文小块与前一块合并，长块按句分割。
        此阶段恢复原有干净逻辑，不再做任何 section_path 前缀匹配。
        """
        merged: List[Chunk] = []
        temp_chunk = None

        for rc in raw_chunks:
            if rc.chunk_type in ['table', 'doc_num']:
                if temp_chunk:
                    merged.append(temp_chunk)
                    temp_chunk = None
                merged.append(rc)
                continue

            if len(rc.content) < self.min_chunk_size:
                if temp_chunk and temp_chunk.section_path == rc.section_path:
                    # 同节点小块合并（向后）
                    temp_chunk.content += "\n" + rc.content
                else:
                    if temp_chunk:
                        merged.append(temp_chunk)
                    temp_chunk = rc

            elif len(rc.content) <= self.max_chunk_size:
                if temp_chunk:
                    merged.append(temp_chunk)
                    temp_chunk = None
                merged.append(rc)

            else:
                if temp_chunk:
                    merged.append(temp_chunk)
                    temp_chunk = None
                # [Bug-11 修复] 按句末标点后切割，保留原始标点
                sentences = re.split(r'(?<=[。！？\n])', rc.content)
                buf = ""
                for s in sentences:
                    if not s.strip():
                        continue
                    if len(buf) + len(s) > self.target_chunk_size and buf:
                        merged.append(Chunk(
                            content=buf.rstrip(), chunk_type="body", section_path=rc.section_path
                        ))
                        buf = s
                    else:
                        buf += s
                if buf.strip():
                    merged.append(Chunk(
                        content=buf.rstrip(), chunk_type="body", section_path=rc.section_path
                    ))

        if temp_chunk:
            merged.append(temp_chunk)

        return merged

    def _merge_pass2(self, chunks: List[Chunk]) -> List[Chunk]:
        """
        Pass2: 向前合并 —— 孤儿标题块前置到下一个 body/table chunk。

        根源：_scan 把标题行追加到 current_body，但若下一行也是标题，
        此 current_body 会被单独 flush，产生"二、主要任务"(6字)一类的无意义孤儿块。
        Pass1 的向后合并无法解决这种情况（因为孤儿需要向前而不是向后合并）。

        [缺陷4 修复] 原代码只处理孤儿后跟 body 的情况，当孤儿标题后紧跟表格时：
          - 原行为：孤儿标题单独入库（噪声 chunk），表格 section_path 缺失章节语境
          - 修复后：table chunk 的 section_path 继承孤儿标题文本，检索时能按章节定位表格
        """
        ORPHAN_THRESHOLD = 20  # 20字以内视为孤儿标题（覆盖所有常见章节标题格式）

        result: List[Chunk] = []
        i = 0
        while i < len(chunks):
            cur = chunks[i]
            # 判断是否为孤儿：body 类型 + 内容极短 + 后面还有 body 或 table chunk
            if (cur.chunk_type == 'body'
                    and len(cur.content.strip()) < ORPHAN_THRESHOLD
                    and i + 1 < len(chunks)
                    and chunks[i + 1].chunk_type in ('body', 'table')):
                next_chunk = chunks[i + 1]
                if next_chunk.chunk_type == 'table':
                    # [缺陷4] 表格：孤儿标题文本注入 section_path，使表格携带章节语境
                    # 若表格已有路径（如「二、（一）」），在已有路径后追加标题，保留层级
                    orphan_text = cur.content.strip()
                    if next_chunk.section_path:
                        next_chunk.section_path = next_chunk.section_path + '/' + orphan_text
                    else:
                        next_chunk.section_path = orphan_text
                else:
                    # body：孤儿标题前置到正文 chunk 开头，使正文获得标题语境
                    next_chunk.content = cur.content.strip() + "\n" + next_chunk.content
                # 当前孤儿不入 result（已被消费）
            else:
                result.append(cur)
            i += 1

        return result



    # ── 阶段三：语境织入 + 质量过滤 ─────────────────────────────────────────

    @staticmethod
    def _is_article_section(path: str) -> bool:
        """
        [缺陷3 修复] 判断给定 section_path 是否是法律条文路径（第X条/第X款 开头）。
        根因：原代码用 RE_ARTICLE_MARK 扫描 prev_content 全文，
              通知/报告中若引用「根据《某条例》第三条规定」，
              该段落会被误判为条文边界，导致 overlap 被错误清零。
        改为：直接检查 section_path 顶层节点，语义精准，无误杀风险。
        """
        if not path:
            return False
        top_node = path.split('/')[0]
        return bool(re.match(r'^第[一二三四五六七八九十百千零\d]+[条款]', top_node))

    def _get_overlap(self, prev_content: str, prev_path: str, cur_path: str) -> str:
        """
        Fix4：跨章节 Overlap 抑制。
        同章节内：注入上一 chunk 末尾句作为语境桥接。
        跨章节边界 / 条文边界：清零 overlap，避免语义污染。
        """
        if not prev_content or self.overlap_size <= 0:
            return ""

        if cur_path and prev_path and cur_path != prev_path:
            prev_top = prev_path.split('/')[0] if prev_path else ''
            cur_top  = cur_path.split('/')[0]  if cur_path  else ''
            if prev_top != cur_top:
                return ""

        # [缺陷3 修复] 条文边界隔离：基于 section_path 而非扫描 content
        # 原代码：RE_ARTICLE_MARK.search(prev_content) 会误杀引用法条的通知正文段落
        # 修复后：只有 section_path 本身是「第X条」型路径，才视为条文边界并清零 overlap
        if self._is_article_section(prev_path):
            return ""

        raw_tail = prev_content[-self.overlap_size:]
        matches = list(self.RE_SENTENCE_END.finditer(raw_tail))
        if matches:
            return raw_tail[matches[0].end():].strip()
        return raw_tail.strip()

    def _enrich(self, merged: List[Chunk]) -> List[Chunk]:
        """面包屑织入、overlap 注入（跨章节抑制）、质量分过滤、纯正文快照保存"""
        final: List[Chunk] = []
        prev_content = ""
        prev_path    = ""

        for mc in merged:
            if mc.chunk_type == 'body':
                char_count = len(mc.content)
                if char_count == 0:
                    continue
                punc_count = len(re.findall(self.RE_PUNCTUATION, mc.content))
                if (char_count > 0 and (punc_count / char_count) > 0.6) and char_count > 50:
                    mc.quality_score = 0.1
                if mc.quality_score < self.min_quality_score:
                    continue

                overlap = self._get_overlap(prev_content, prev_path, mc.section_path)

                # [P0-5A] 向量精准度修复：面包屑织入前保存纯正文快照
                mc.raw_content = mc.content

                breadcrumb = f"[语篇语境: {mc.section_path}]\n" if mc.section_path else ""
                final_text = breadcrumb
                if overlap and overlap not in mc.content:
                    final_text += f"...{overlap}"
                final_text += mc.content
                mc.content = final_text

                prev_content = mc.content
                prev_path    = mc.section_path
            else:
                prev_content = ""
                prev_path    = ""
                if mc.section_path and mc.chunk_type == 'table':
                    # [BUG-05 根治] 表格面包屑织入前保存纯表格文本快照（供向量化使用）
                    mc.raw_content = mc.content
                    mc.content = f"[语篇语境: {mc.section_path}] 表格摘要：\n" + mc.content

            final.append(mc)

        return final
