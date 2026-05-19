# PDF 专用变换管道（pdf_transforms 子包）
# 职责：编排和执行 PDF 内容变换（裁页、旋转矫正、OCR 识别等），
#       与 Parser 层（文字提取）解耦，独立可测试。
# 扩展方式：新增 XxxTransform 实现 BaseTransform 协议，
#           在 PdfProcessor.__init__ 的 _transforms 列表中追加即可，主流程零改动。
#
# Phase 1（已实现）：PageSkipTransform - 物理裁去 PDF 前 N 页（PyMuPDF）
# Phase 2（stub）：DeskewTransform - 旋转矫正 + 偏斜纠正（OpenCV + PyMuPDF）
# Phase 2（stub）：OcrPipelineTransform - PaddleOCR 版面分析 + 表格识别
