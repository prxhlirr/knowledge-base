"""
业务功能：专用 PDF 解析器，基于 pdfplumber 的字符坐标重建行结构。

设计决策（对比 MarkItDown / pypdf 方案）：
  - pypdf：纯文字流提取，无坐标信息，政府 PDF 常出现「标题行与正文无空行分隔」的问题。
  - MarkItDown：通用兜底，HTML/Markdown 转换，无法处理复杂表格和多栏布局。
  - pdfplumber：利用字符的 (x0, y0, x1, y1) 坐标，可以：
      1. 按 y 坐标聚合同行字符，还原真实行结构（解决 D2）
      2. 识别表格并转为 Markdown 格式（解决 PDF 表格乱序问题）
      3. 检测行间距，自动在段落之间插入空行（为 HierarchyDetector 提供上下文）

关键流程：
  1. 逐页提取：优先抽取表格（转 Markdown），再按行坐标聚合普通文本行
  2. 行合并：y 差值 < line_threshold 的字符归为同一行，同行字符按 x 排序拼接
  3. 段落边界检测：连续行间距 > para_threshold 时插入空行
  4. 降级路径：pdfplumber 不可用时 import 失败，调用方捕获 ImportError 并降级至兜底

兼容性限制：
  - 扫描件 PDF（纯图片）无法提取文字，将返回空字符串 → 调用方降级至 OCR 路径
  - 加密 PDF pdfplumber 抛 PDFPasswordIncorrect → 调用方捕获后返回错误提示
"""

import re


class PdfParser:
    """
    基于 pdfplumber 坐标感知的 PDF 解析器。
    实现 ParserFactory 的 Parser 接口契约（can_parse / parse）。
    """

    # 行聚合参数（单位：points，1 point ≈ 0.35mm）
    LINE_Y_THRESHOLD = 2.0    # y 差值 < 2 的字符视为同行（处理基线微小浮动）
    PARA_Y_THRESHOLD = 8.0    # 行间距 > 8 时视为段落边界，插入空行

    def can_parse(self, file_path: str) -> bool:
        """仅处理 .pdf 扩展名"""
        return file_path.lower().endswith(".pdf")

    def parse(self, file_path: str) -> str:
        """
        完整 PDF 解析流程：表格识别 + 坐标行重建 + 段落边界检测。
        关键流程：逐页处理 → 先提取表格（Markdown格式）→ 再按坐标聚合文本行 → 段落空行插入
        降级路径：pdfplumber 不可用时抛 ImportError（调用方 ParserFactory 捕获后转兜底路径）
        """
        try:
            import pdfplumber
        except ImportError:
            print("⚠️ [PdfParser] pdfplumber 未安装，降级至 MarkItDownParser")
            raise ImportError("pdfplumber not available")

        all_lines = []
        page_count = 0

        try:
            with pdfplumber.open(file_path) as pdf:
                page_count = len(pdf.pages)
                for page_idx, page in enumerate(pdf.pages):
                    page_lines = self._extract_page(page, page_idx)
                    all_lines.extend(page_lines)
                    # 页面之间插入空行（保留页边界感知）
                    if page_idx < page_count - 1:
                        all_lines.append("")

        except Exception as e:
            error_msg = str(e)
            if "password" in error_msg.lower() or "encrypt" in error_msg.lower():
                print(f"⚠️ [PdfParser] PDF 已加密，无法解析: {file_path}")
                return "【PDF 已加密】：文件受密码保护，无法自动提取文字内容。请解密后重新上传。"
            print(f"⚠️ [PdfParser] pdfplumber 解析失败: {e}，降级至兜底")
            return ""  # 返回空字符串触发 ParserFactory 降级

        result = "\n".join(all_lines).strip()

        if not result:
            print(f"⚠️ [PdfParser] 内容为空（可能是扫描件），降级至 MarkItDown: {file_path}")
            return ""

        print(f"[PdfParser] 成功解析 {page_count} 页，共 {len(result)} 字符: {file_path}")
        return result

    def _extract_page(self, page, page_idx: int) -> list:
        """
        单页提取逻辑：先提取表格（Markdown格式），再按坐标聚合剩余文本行。
        关键流程：
          1. 识别页内表格 bbox（边界框）
          2. 非表格区域：按 y 坐标聚合字符 → 行文本 → 段落空行检测
          3. 按 y 顺序将表格 Markdown 和普通文本行合并输出
        """
        lines_out = []

        # Step1：识别并提取表格（转 Markdown），记录表格覆盖的 y 区间
        table_y_ranges = []
        try:
            tables = page.extract_tables()
            table_bboxes = page.find_tables()
            for t_idx, (table_data, t_bbox) in enumerate(zip(tables, table_bboxes)):
                if not table_data:
                    continue
                md_table = self._table_to_markdown(table_data)
                if md_table:
                    lines_out.append((t_bbox.bbox[1], md_table))  # (y0, content)
                    table_y_ranges.append((t_bbox.bbox[1], t_bbox.bbox[3]))
        except Exception as te:
            print(f"  [PdfParser] 页{page_idx + 1} 表格提取失败（已跳过）: {te}")

        # Step2：按 y 坐标聚合非表格区域的字符为行
        chars = page.chars
        if not chars:
            return [item[1] for item in sorted(lines_out, key=lambda x: x[0])]

        # 过滤掉在表格 y 区间内的字符（避免重复）
        def in_table_area(char_y0):
            return any(y_min <= char_y0 <= y_max for y_min, y_max in table_y_ranges)

        text_chars = [c for c in chars if not in_table_area(c.get("y0", 0))]

        # 按 y0 排序后聚合同行字符（y 差值 < LINE_Y_THRESHOLD 视为同行）
        if text_chars:
            text_chars_sorted = sorted(text_chars, key=lambda c: (round(c.get("y0", 0) / self.LINE_Y_THRESHOLD), c.get("x0", 0)))
            row_groups = []
            cur_group = [text_chars_sorted[0]]
            for ch in text_chars_sorted[1:]:
                prev_y = cur_group[-1].get("y0", 0)
                cur_y = ch.get("y0", 0)
                if abs(cur_y - prev_y) < self.LINE_Y_THRESHOLD:
                    cur_group.append(ch)
                else:
                    row_groups.append(cur_group)
                    cur_group = [ch]
            row_groups.append(cur_group)

            # 对每个行组：按 x 排序后拼接字符，并检测段落边界（插入空行）
            prev_avg_y = None
            for group in row_groups:
                group_sorted = sorted(group, key=lambda c: c.get("x0", 0))
                line_text = "".join(c.get("text", "") for c in group_sorted).strip()
                if not line_text:
                    continue

                # 段落边界检测：行间距 > PARA_Y_THRESHOLD 时插入空行
                avg_y = sum(c.get("y0", 0) for c in group) / len(group)
                if prev_avg_y is not None and (avg_y - prev_avg_y) > self.PARA_Y_THRESHOLD:
                    lines_out.append((avg_y - self.PARA_Y_THRESHOLD - 0.1, ""))  # 空行
                lines_out.append((avg_y, line_text))
                prev_avg_y = avg_y

        # Step3：按 y 顺序合并所有内容（表格 Markdown + 普通文本行）
        sorted_items = sorted(lines_out, key=lambda x: x[0])
        return [item[1] for item in sorted_items]

    def _table_to_markdown(self, table_data: list) -> str:
        """
        将 pdfplumber 提取的表格数据转为 Markdown 格式。
        关键流程：清洗单元格 → 计算列数 → 生成 header + separator + rows
        空表或单行表直接拼接文本（不生成 Markdown 表格）。
        """
        if not table_data or len(table_data) < 1:
            return ""

        # 清洗单元格：None → 空字符串，多余空白压缩，换行变空格
        def clean_cell(v):
            if v is None:
                return ""
            return re.sub(r"\s+", " ", str(v).strip())

        cleaned = [[clean_cell(cell) for cell in row] for row in table_data]

        # 确定最大列数
        max_cols = max(len(row) for row in cleaned) if cleaned else 0
        if max_cols == 0:
            return ""

        # 补齐行长度
        rows = [row + [""] * (max_cols - len(row)) for row in cleaned]

        if len(rows) == 1:
            # 单行表：直接拼为文本行
            return " | ".join(rows[0])

        # 多行表：Markdown 格式
        header = "| " + " | ".join(rows[0]) + " |"
        separator = "| " + " | ".join(["---"] * max_cols) + " |"
        body = "\n".join("| " + " | ".join(row) + " |" for row in rows[1:])
        return "\n".join([header, separator, body])
