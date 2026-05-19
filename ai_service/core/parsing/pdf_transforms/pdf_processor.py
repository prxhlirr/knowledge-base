"""
业务功能：PDF 变换链编排器，负责按顺序执行所有已注册的 Transform 实例。
关键流程：
  1. 遍历 self._transforms，对每个 Transform 判断 should_apply(opts)
  2. 满足条件则调用 apply(current_path, opts)
  3. apply 返回非 None 路径 → 替换 current_path，追加至 temp_files 备清理
  4. apply 返回 None → 跳过，current_path 保持不变
  5. 返回 (final_path, temp_files)：final_path 给后续 Parser 使用，
     temp_files 由 ParserFactory 在 finally 中统一清理
设计原则（开闭原则）：
  新增 Phase 2 变换时，只需在 _transforms 列表中追加，无需修改此类任何逻辑。
  变换顺序：裁页（PageSkip）→ 旋转矫正（Deskew）→ OCR（OcrPipeline）
"""
import os


class PdfProcessor:
    """
    PDF 变换链编排器。负责协调 Phase 1/2 的所有 PDF 预处理 Transform。
    约定：仅对 .pdf 文件调用，由 ParserFactory 在路由前触发。
    """

    def __init__(self):
        # 按顺序注册变换链：
        #   Phase 1（已实现）：PageSkipTransform  — 物理裁去 PDF 前 N 页
        #   Phase 2（已实现）：ScannedDetectorTransform — 探测扫描件，写 opts["scanned"]
        #   Phase 2（已实现）：DeskewTransform    — 旋转矫正（scanned=True 时激活）
        #   Phase 2（已实现）：OcrPipelineTransform — PaddleOCR PP-Structure（scanned=True 时激活）
        # 设计原则（开闭原则）：
        #   ScannedDetectorTransform.apply() 返回 None 时，process() 的
        #   `if result and result != current_path and os.path.exists(result)` 条件为 False，
        #   current_path 保持不变，opts 以引用传递天然感知修改，无需任何 process() 逻辑改动。
        from core.parsing.pdf_transforms.page_skip import PageSkipTransform
        from core.parsing.pdf_transforms.scanned_detector import ScannedDetectorTransform
        from core.parsing.pdf_transforms.deskew import DeskewTransform
        from core.parsing.pdf_transforms.ocr_pipeline import OcrPipelineTransform

        self._transforms = [
            PageSkipTransform(),            # Phase 1：物理裁去前 N 页（已实现）
            ScannedDetectorTransform(),     # Phase 2：自动探测扫描件（写 opts["scanned"]）
            DeskewTransform(),              # Phase 2：旋转矫正（scanned=True 时激活）
            OcrPipelineTransform(),         # Phase 2：PaddleOCR PP-Structure（scanned=True 时激活）
        ]

    def process(self, pdf_path: str, opts: dict) -> "tuple[str, list]":
        """
        顺序执行变换链，返回最终 PDF 路径和所有产生的临时文件列表。
        入参：
          pdf_path - 原始 PDF 文件路径
          opts     - parse_options 字典（来自 ext_metadata）
        返回：
          (final_path, temp_files)
          final_path  - 经变换后的文件路径（可能等于 pdf_path，若无变换生效）
          temp_files  - 所有产生的临时文件路径列表（供调用方 finally 清理）
        """
        current_path = pdf_path
        temp_files = []

        for transform in self._transforms:
            if not transform.should_apply(opts):
                continue
            try:
                result = transform.apply(current_path, opts)
            except Exception as e:
                # 单个 Transform 抛出未预期异常时，降级跳过，不阻断后续处理
                print(f"⚠️ [PdfProcessor] {type(transform).__name__} 执行异常，跳过: {e}")
                continue

            if result and result != current_path and os.path.exists(result):
                temp_files.append(result)
                current_path = result

        return current_path, temp_files
