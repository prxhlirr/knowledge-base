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

import os
import re
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Any
from core.parsing.document_element import DocumentElement as UnifiedDocumentElement


@dataclass
class DocElement:
    """
    语义化文档元素：承载文本、物理坐标、层级路径以及溯源元数据。
    支持双轨制存储：text 用于向量检索，metadata 用于 UI 溯源。
    """
    type: str                     # title, paragraph, table, list
    text: str                     # 包含路径前缀的文本（用于 Embedding / 检索匹配）
    raw_text: str                 # 原始纯净文本
    page_idx: int                 # 页码 (0 索引)
    bbox: List[float]             # 物理坐标 [x0, y0, x1, y1]
    heading_hierarchy: List[str] = field(default_factory=list) # 完整的标题层级路径数组

    def to_dict(self) -> Dict[str, Any]:
        return {
            "type": self.type,
            "text": self.text,
            "raw_text": self.raw_text,
            "page_idx": self.page_idx,
            "bbox": self.bbox,
            "metadata": {
                "heading_hierarchy": self.heading_hierarchy
            }
        }


class HeadingTracker:
    """
    追踪文档解析过程中的标题层级结构，支持层级路径生成。
    核心逻辑：根据标题特征判定 level，动态调整 stack 深度。
    """
    def __init__(self):
        self.stack: List[str] = []

    def update(self, text: str, element_type: str):
        if not text: return
        
        is_h = False
        level = 1
        
        # 判定是否为标题
        # 模式判定：数字章节号是最高优先级的结构信号
        text_s = text.strip()
        if re.match(r"^\d+\.\d+\.\d+\s+", text_s):
            is_h = True
            level = 3
        elif re.match(r"^\d+\.\d+\s+", text_s):
            is_h = True
            level = 2
        elif re.match(r"^第[一二三四五六七八九十百]+[章节回]\s+", text_s):
            is_h = True
            level = 1
        # 模式 2: OCR 显式标记为 title (或 Markdown 源码)
        elif element_type == "title":
            is_h = True
            if text.startswith("### "): level = 3
            elif text.startswith("## "): level = 2
            else: level = 1
                
        if is_h:
            # 调整栈深：level=1 -> stack[:0]; level=2 -> stack[:1]
            # 同级或更高级标题出现时，清理同级及子层级
            if len(self.stack) >= level:
                self.stack = self.stack[:level-1]
            # 提取纯净标题文本 (剥离 Markdown 符号和章节号)
            clean_text = re.sub(r"^(#+\s+|第.*?[章节回]\s+|\d+(\.\d+)*\s+)", "", text).strip()
            if clean_text:
                self.stack.append(clean_text)

    def get_path_prefix(self) -> str:
        """返回用于注入正文的前缀：[Context: A > B] """
        if not self.stack: return ""
        return f"[Context: {' > '.join(self.stack)}] "

    def get_hierarchy(self) -> List[str]:
        return list(self.stack)


class PdfParser:
    """
    基于 pdfplumber 坐标感知的 PDF 解析器。
    实现 ParserFactory 的 Parser 接口契约（can_parse / parse）。
    """

    # 行聚合参数（单位：points，1 point ≈ 0.35mm）
    LINE_Y_THRESHOLD = 2.0    # y 差值 < 2 的字符视为同行（处理基线微小浮动）
    PARA_Y_THRESHOLD = 8.0    # 行间距 > 8 时视为段落边界，插入空行

    # 图片区域 OCR 触发阈值：
    #   图片 bbox 内原生字符数 < 此值 → 认为图片文字未被 pdfplumber 覆盖 → 触发 OCR
    _IMG_SPARSE_CHAR_THRESHOLD = 5
    # 最小图片尺寸（points）：低于此值的图片通常为装饰性背景图案，跳过 OCR
    _IMG_MIN_SIZE = 30.0

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

        # 将当前文件路径存为实例变量，供 _extract_images_ocr 内部使用 fitz 渲染
        self._current_pdf_path = file_path
        all_elements: List[DocElement] = []
        page_count = 0
        tracker = HeadingTracker()

        try:
            with pdfplumber.open(file_path) as pdf:
                page_count = len(pdf.pages)
                for page_idx, page in enumerate(pdf.pages):
                    page_elements = self._extract_page(page, page_idx, tracker)
                    all_elements.extend(page_elements)

        except Exception as e:
            error_msg = str(e)
            if "password" in error_msg.lower() or "encrypt" in error_msg.lower():
                print(f"⚠️ [PdfParser] PDF 已加密，无法解析: {file_path}")
                return "【PDF 已加密】：文件受密码保护，无法自动提取文字内容。请解密后重新上传。"
            print(f"⚠️ [PdfParser] pdfplumber 解析失败: {e}，降级至兜底")
            return ""  # 返回空字符串触发 ParserFactory 降级

        # 结果聚合：双轨制注入已在 DocElement 构造时完成
        # 拼接 DocElement.text 作为最终 RAG 语料，保留页间距
        result = "\n\n".join([el.text for el in all_elements]).strip()

        if not result:
            print(f"⚠️ [PdfParser] 内容为空（可能是扫描件），降级至 MarkItDown: {file_path}")
            return ""

        print(f"[PdfParser] 成功执行语义化解析，共 {page_count} 页，{len(all_elements)} 个语义元素，{len(result)} 字符")
        return result

    def parse_elements(self, file_path: str) -> List[UnifiedDocumentElement]:
        """结构化解析入口：保留页码、bbox 与元素类型，供噪声分类器使用。"""
        try:
            import pdfplumber
        except ImportError:
            print("⚠️ [PdfParser] pdfplumber 未安装，无法结构化解析")
            raise ImportError("pdfplumber not available")

        self._current_pdf_path = file_path
        tracker = HeadingTracker()
        all_elements: List[DocElement] = []
        try:
            with pdfplumber.open(file_path) as pdf:
                for page_idx, page in enumerate(pdf.pages):
                    all_elements.extend(self._extract_page(page, page_idx, tracker))
        except Exception as err:
            print(f"⚠️ [PdfParser] 结构化解析失败: {err}")
            return []

        return [
            UnifiedDocumentElement(
                text=el.raw_text or el.text,
                type=el.type or "paragraph",
                page_idx=el.page_idx,
                bbox=el.bbox,
                source="pdf",
                meta={"heading_hierarchy": el.heading_hierarchy, "indexed_text": el.text},
            )
            for el in all_elements
            if (el.raw_text or el.text or "").strip()
        ]

    def _extract_page(self, page, page_idx: int, tracker: HeadingTracker) -> List[DocElement]:
        """
        单页提取逻辑：先提取表格（Markdown格式），再按坐标聚合剩余文本行。
        返回 DocElement 列表，支持标题路径自动注入。
        """
        raw_lines = []  # 暂存四元组：(y0, text, type, bbox)

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
                    # 表格类型标记为 table
                    raw_lines.append((t_bbox.bbox[1], md_table, "table", list(t_bbox.bbox)))
                    table_y_ranges.append((t_bbox.bbox[1], t_bbox.bbox[3]))
        except Exception as te:
            print(f"  [PdfParser] 页{page_idx + 1} 表格提取失败（已跳过）: {te}")

        # Step2：按 y 坐标聚合非表格区域的字符为行
        chars = page.chars or []

        if chars:
            # 过滤掉在表格 y 区间内的字符（避免重复）
            def in_table_area(char_top):
                return any(y_min <= char_top <= y_max for y_min, y_max in table_y_ranges)

            text_chars = [c for c in chars if not in_table_area(c.get("top", 0))]

            # 按 top 排序后聚合同行字符（y 差值 < LINE_Y_THRESHOLD 视为同行）
            if text_chars:
                text_chars_sorted = sorted(text_chars, key=lambda c: c.get("top", 0))
                row_groups = []
                cur_group = [text_chars_sorted[0]]
                for ch in text_chars_sorted[1:]:
                    prev_y = cur_group[-1].get("top", 0)
                    cur_y = ch.get("top", 0)
                    if abs(cur_y - prev_y) < self.LINE_Y_THRESHOLD:
                        cur_group.append(ch)
                    else:
                        row_groups.append(cur_group)
                        cur_group = [ch]
                row_groups.append(cur_group)

                # 对行组进行段落聚合：行间距 < PARA_Y_THRESHOLD 视为同一段落
                para_groups = []
                if row_groups:
                    cur_para = [row_groups[0]]
                    for i in range(1, len(row_groups)):
                        prev_avg_y = sum(c.get("top", 0) for c in row_groups[i-1]) / len(row_groups[i-1])
                        cur_avg_y = sum(c.get("top", 0) for c in row_groups[i]) / len(row_groups[i])
                        
                        # 判定逻辑：物理间距小于阈值则合并
                        if abs(cur_avg_y - prev_avg_y) < self.PARA_Y_THRESHOLD:
                            cur_para.append(row_groups[i])
                        else:
                            para_groups.append(cur_para)
                            cur_para = [row_groups[i]]
                    para_groups.append(cur_para)

                for para in para_groups:
                    para_text_parts = []
                    for group in para:
                        group_sorted = sorted(group, key=lambda c: c.get("x0", 0))
                        line_text = "".join(c.get("text", "") for c in group_sorted).strip()
                        if line_text:
                            para_text_parts.append(line_text)
                    
                    if not para_text_parts:
                        continue
                        
                    # 中文段落通常直接拼接，无需空格
                    combined_text = "".join(para_text_parts)
                    
                    # 聚合该段落所有字符的物理 bbox
                    all_ch = [c for g in para for c in g]
                    min_x = min(c.get("x0", 0) for c in all_ch)
                    max_x = max(c.get("x1", 0) for c in all_ch)
                    min_y = min(c.get("top", 0) for c in all_ch)
                    max_y = max(c.get("bottom", 0) for c in all_ch)
                    
                    raw_lines.append((min_y, combined_text, "paragraph", [min_x, min_y, max_x, max_y]))

        # Step3：对页面内嵌图片区域执行补充 OCR
        img_ocr_items = self._extract_images_ocr(page, page_idx)
        raw_lines.extend(img_ocr_items)

        # Step4：全页 OCR 熔断判定
        total_text = "".join(str(item[1]) for item in raw_lines if item and len(item) >= 2)
        effective_text = re.sub(r"\s+", "", total_text)
        if len(effective_text) < 10:
            emergency_items = self._emergency_full_page_ocr(page, page_idx)
            if emergency_items:
                # 触发熔断：用高质量的整页 OCR 覆盖
                raw_lines = emergency_items

        # Step5：按 y 顺序构造语义化 DocElement 序列（执行路径注入）
        sorted_raw = sorted(raw_lines, key=lambda x: x[0])
        page_elements: List[DocElement] = []
        
        for y, text, r_type, bbox in sorted_raw:
            if not text or not text.strip():
                continue
                
            # 1. 状态机动态更新标题层级栈
            tracker.update(text, r_type)
            
            # 2. 注入前缀：如果当前元素本身不是标题，则注入路径前缀增强 Embedding 检索性
            prefix = tracker.get_path_prefix() if r_type != "title" else ""
            
            # 3. 封装 DocElement 对象
            el = DocElement(
                type=r_type,
                text=prefix + text,
                raw_text=text,
                page_idx=page_idx,
                bbox=bbox,
                heading_hierarchy=tracker.get_hierarchy()
            )
            page_elements.append(el)
            
        return page_elements

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

    def _extract_images_ocr(self, page, page_idx: int) -> list:
        """
        业务功能：对 pdfplumber 页面内嵌入的图片区域执行 PaddleOCR 补充识别，
                  解决混合型 PDF（原生文字 + 图片文字共存）中图片文字丢失的问题。

        核心策略：
          1. 遍历 page.images 获取所有嵌入图片的 bbox 坐标
          2. 检查图片区域内原生字符密度：字符数 >= _IMG_SPARSE_CHAR_THRESHOLD 则跳过
             （说明 pdfplumber 已覆盖该区域，无需重复 OCR）
          3. 使用 fitz（PyMuPDF）将图片区域渲染为 200 DPI numpy RGB 数组
          4. 调用 PPStructure 版面分析，提取文字/标题内容（丢弃 figure 装饰区域）
          5. 以图片 top 坐标作为 y_pos，返回 (y_pos, text) 列表供 _extract_page 排序合并

        降级策略：
          - fitz 或 paddleocr 未安装 → ImportError 静默捕获，返回空列表
          - 单张图片 OCR 失败 → Exception 静默捕获，跳过该图片，不阻断整页解析
          - fitz 打开 PDF 失败 → 整个图片 OCR 步骤跳过，原生文本正常返回

        :param page:      pdfplumber Page 对象（携带 .images 和 .chars）
        :param page_idx:  当前页索引（用于 fitz 定位目标页）
        :return:          [(y_pos: float, text: str)] 列表，可能为空
        """
        images = getattr(page, "images", []) or []
        if not images:
            return []

        # 懒加载依赖：任意依赖缺失则静默跳过，不影响普通 PDF 的解析路径
        try:
            import fitz
            import numpy as np
        except ImportError:
            return []

        try:
            # 复用 OcrPipelineTransform 已初始化的 PPStructure 单例，避免重复加载模型
            from core.parsing.pdf_transforms.ocr_pipeline import _get_engine, _extract_region_text
            engine = _get_engine()
        except Exception as init_err:
            print(f"  ⚠️ [PdfParser] 内嵌图片 OCR 引擎加载失败 (page={page_idx + 1})，跳过该步。报错详情: {init_err}")
            return []

        results = []
        all_chars = page.chars or []
        # 渲染分辨率（DPI）：200 DPI 对图片嵌入文字识别效果与性能的平衡点
        dpi_scale = 200 / 72.0
        mat = fitz.Matrix(dpi_scale, dpi_scale)

        try:
            doc = fitz.open(self._current_pdf_path)
            fitz_page = doc[page_idx]

            for img_meta in images:
                try:
                    x0     = float(img_meta.get("x0", 0))
                    top    = float(img_meta.get("top", 0))     # pdfplumber: 距页面顶部距离
                    x1     = float(img_meta.get("x1", 0))
                    bottom = float(img_meta.get("bottom", 0))

                    # 过滤过小的装饰性图片（如水印、印章小图标）
                    if (x1 - x0) < self._IMG_MIN_SIZE or (bottom - top) < self._IMG_MIN_SIZE:
                        continue

                    # 检查图片 bbox 内已有的原生字符数
                    # 原生字符充足说明 pdfplumber 已提取到文字，跳过避免重复
                    region_chars = [
                        c for c in all_chars
                        if x0 <= c.get("x0", 0) <= x1
                        and top <= c.get("top", 0) <= bottom
                    ]
                    if len(region_chars) >= self._IMG_SPARSE_CHAR_THRESHOLD:
                        continue

                    # pdfplumber 的 top/bottom 与 fitz 坐标系一致（原点左上角，y 向下增大）
                    clip_rect = fitz.Rect(x0, top, x1, bottom)
                    pix = fitz_page.get_pixmap(
                        matrix=mat,
                        clip=clip_rect,
                        colorspace=fitz.csRGB
                    )
                    img_array = np.frombuffer(
                        pix.samples, dtype=np.uint8
                    ).reshape(pix.height, pix.width, 3)

                    # PPStructure 版面分析（复用 OcrPipeline 单例，无额外显存开销）
                    structure_result = engine(img_array)
                    
                    for region in (structure_result or []):
                        r_type = str(region.get("type", "")).lower()
                        # 丢弃图片/图注（公章、底纹等装饰性区域）
                        if r_type in ("figure", "figure_caption"):
                            continue
                        text = _extract_region_text(region.get("res", {}))
                        if text.strip():
                            # 物理还原坐标：针对被裁剪图片的局部坐标 [rx0, ry0, rx1, ry1]
                            r_bbox = region.get("bbox", [0, 0, 0, 0])
                            # 映射回全页坐标：x = x0 + rx/scale, y = top + ry/scale
                            abs_top = top + r_bbox[1] / dpi_scale
                            abs_bbox = [x0 + r_bbox[0]/dpi_scale, top + r_bbox[1]/dpi_scale, x0 + r_bbox[2]/dpi_scale, top + r_bbox[3]/dpi_scale]
                            results.append((abs_top, text.strip(), r_type, abs_bbox))

                except Exception as img_err:
                    print(f"  [PdfParser] 图片区域 OCR 跳过 (page={page_idx + 1}): {img_err}")

            doc.close()

        except Exception as fitz_err:
            print(f"  [PdfParser] fitz 渲染图片区域失败，跳过图片OCR (page={page_idx + 1}): {fitz_err}")

        return results

    def _emergency_full_page_ocr(self, page, page_idx: int) -> list:
        """
        业务功能：终极熔断保险丝。针对混合文档中的“无文本流页”（XForm封装的扫描图），
                  强制执行整页 200 DPI RGB 光栅化渲染，并调用 PPStructure 进行全版面抓取。
        
        返回：[(original_y: float, text: str)]，还原相对物理坐标供段落重排
        """
        try:
            import fitz
            import numpy as np
            # 复用引擎单例，避免并发导入和内存超载
            from core.parsing.pdf_transforms.ocr_pipeline import _get_engine, _extract_region_text
            engine = _get_engine()
        except Exception as init_err:
            # 将隐藏的异常显式打印到控制台，摧毁沉默黑盒
            print(f"  ⚠️ [PdfParser] 终极保险丝 OCR 引擎初始化失败 (page={page_idx + 1})，已安全跳过。报错详情: {init_err}")
            return []

        results = []
        dpi_scale = 200 / 72.0
        mat = fitz.Matrix(dpi_scale, dpi_scale)

        try:
            doc = fitz.open(self._current_pdf_path)
            fitz_page = doc[page_idx]

            # 1. 全图光栅化渲染
            pix = fitz_page.get_pixmap(matrix=mat, colorspace=fitz.csRGB)
            img_array = np.frombuffer(pix.samples, dtype=np.uint8).reshape(
                pix.height, pix.width, 3
            )

            # 2. 物理视觉感知分析
            structure_result = engine(img_array)
            page_regions = []

            for region in (structure_result or []):
                r_type = str(region.get("type", "")).lower().strip()
                # 自动剔除装饰性背景图/印章框，只吸纳实质文本语义
                if r_type in ("figure", "figure_caption"):
                    continue
                
                text = _extract_region_text(region.get("res", {}))
                if text.strip():
                    # 计算在 200 DPI 下的顶边 y 坐标，并做归一化缩放映射回 Points 物理空间
                    bbox = region.get("bbox", [0, 0, 0, 0])
                    y_pos_pixel = bbox[1] if len(bbox) >= 2 else 0
                    original_y = y_pos_pixel / dpi_scale
                    original_bbox = [b / dpi_scale for b in bbox]
                    
                    # 对 OCR 发现的 Title 加持 Markdown 层级标示
                    content = f"## {text.strip()}" if r_type == "title" else text.strip()
                    page_regions.append((original_y, content, r_type, original_bbox))

            # ── [第一性原理降维破局：穿透式 OCR 兜底补网] ──
            # 现象：如果遍历完版面分析结果后，发现 page_regions 依然为空。
            # 根因：极大可能是 Layout 模型在此特定排版的 PDF 页上发生高维感知致盲（如 EMPTY result）。
            # 策略：立刻绕过版面层，动态拉取纯 OCR 穿透引擎强制扫码，并将文字与 original_y 像素换算后归并。
            if not page_regions:
                try:
                    print(f"  🚨 [PdfParser] Layout 视觉失明！启动【纯OCR穿透式兜底】(Page {page_idx + 1})...")
                    from core.parsing.pdf_transforms.ocr_pipeline import _get_pure_ocr_engine
                    pure_engine = _get_pure_ocr_engine()
                    
                    # 执行纯检测与识别模式
                    pure_res = pure_engine.ocr(img_array, cls=False)
                    
                    if pure_res and pure_res[0]:
                        for line in pure_res[0]:
                            box = line[0]  # 字符外接多边形坐标，结构为 [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]
                            txt, _ = line[1]  # ('文本内容', 置信度分数)
                            
                            if txt and txt.strip():
                                # 使用左上角顶点的 y 像素坐标换算回 pdfplumber 物理 Points 空间
                                y_pos_pixel = float(box[0][1])
                                original_y = y_pos_pixel / dpi_scale
                                original_bbox = [box[0][0]/dpi_scale, box[0][1]/dpi_scale, box[2][0]/dpi_scale, box[2][1]/dpi_scale]
                                page_regions.append((original_y, txt.strip(), "paragraph", original_bbox))
                                
                        if page_regions:
                            print(f"  ✅ [PdfParser] 【穿透OCR】拯救成功！在致盲页重新夺回 {len(page_regions)} 行核心文字。")
                except Exception as pure_err:
                    print(f"  ⚠️ [PdfParser] 【穿透OCR】降级重试在该页遭遇底层异常: {pure_err}")

            # 按渲染后的 y 坐标进行重排序，完美对齐人类阅读逻辑顺序
            page_regions.sort(key=lambda x: x[0])
            results = page_regions

            if results:
                print(
                    f"  🚨 [PdfParser] 激活终极保险丝机制！第 {page_idx + 1} 页全栅格化 OCR"
                    f" 成功拦截并提取 {len(results)} 个文本语义块。"
                )

            # 显式清除渲染缓冲与变量，防止局部 OOM
            del pix
            import gc
            gc.collect()
            doc.close()

        except Exception as err:
            print(f"  ⚠️ [PdfParser] 终极保险丝 OCR 执行时遭遇底层异常 (page={page_idx + 1}): {err}")

        return results
