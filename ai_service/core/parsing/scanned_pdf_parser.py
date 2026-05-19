"""
业务功能（Phase 2 实现）：接收 OcrPipelineTransform 输出的已提取文本，
  完成扫描件内容的最终格式化输出（文字 + Markdown 表格拼接）。

当前状态：ACTIVE —— can_parse() 识别 _ocr_result.txt 后缀，ParserFactory 路由至此。

实现说明：
  OcrPipeline.apply() 已将版面分析结果写入 _ocr_result.txt（Markdown 格式）。
  本 Parser 的职责极简：直接读取文件内容并返回，无需任何额外处理。
  下游管线（TextCleaner → SemanticChunker）接管后续处理。

ParserFactory 注册顺序（Phase 2 激活后）：
  ScannedPdfParser   ← [已移至 PdfParser 前] 优先接管 _ocr_result.txt
  PdfParser          ← 处理普通 .pdf（pdfplumber 坐标行重建）
  MarkItDownParser   ← 通用兜底

接口遵循：
  AbstractParser 协议（base_parser.py），与现有 PdfParser / DocxParser 完全一致。
"""

import json
import os

from core.parsing.document_element import DocumentElement


class ScannedPdfParser:
    """
    Phase 2 实现：扫描件 OCR 结果解析器。
    接收 OcrPipelineTransform 输出的 Markdown 格式文本文件，直接返回内容。
    """

    def can_parse(self, file_path: str) -> bool:
        """
        识别 OcrPipelineTransform 的输出文件。
        匹配条件：文件路径以 _ocr_result.txt 结尾（tempfile 创建的临时文件名格式）。
        不会与 PdfParser 产生冲突（PdfParser 仅匹配 .pdf 后缀）。
        """
        return file_path.endswith("_ocr_result.txt")

    def parse(self, file_path: str) -> str:
        """
        业务功能：读取 OcrPipeline 写出的 Markdown 文本文件并返回。
        关键流程：UTF-8 读取 → 返回（内容已是 Markdown 格式，无需二次处理）。
        TextCleaner 将在下游执行去噪（规则1-8），包含扫描件专属规则7/8。

        :param file_path: _ocr_result.txt 临时文件路径
        :return:          Markdown 格式文本字符串
        """
        with open(file_path, encoding="utf-8") as f:
            content = f.read()
        print(f"[ScannedPdfParser] 读取 OCR 结果: {len(content)} 字符 从 {file_path}")
        return content

    def parse_elements(self, file_path: str) -> list:
        json_path = file_path + ".elements.json"
        if os.path.exists(json_path):
            try:
                with open(json_path, encoding="utf-8") as f:
                    payload = json.load(f)
                return [
                    DocumentElement(
                        text=item.get("text", ""),
                        type=item.get("type", "paragraph"),
                        page_idx=item.get("page_idx"),
                        bbox=item.get("bbox"),
                        source=item.get("source", "ocr"),
                    )
                    for item in payload
                    if str(item.get("text", "")).strip()
                ]
            except Exception as err:
                print(f"⚠️ [ScannedPdfParser] OCR 元素 JSON 读取失败，回退 txt: {err}")

        text = self.parse(file_path)
        return [
            DocumentElement(text=line.strip(), type="paragraph", source="ocr")
            for line in text.splitlines()
            if line.strip()
        ]
