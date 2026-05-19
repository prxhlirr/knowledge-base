"""
业务功能（Phase 2 实现）：PDF 旋转矫正（Deskew）。
触发条件（Phase 2 激活）：parse_options["scanned"] = True（由 ScannedDetectorTransform 写入）

实现策略：
  1. 用 fitz（PyMuPDF）读取每页 page.rotation 元数据
  2. 对含非零旋转的页执行 page.set_rotation(0) 矫正
  3. 有旋转时 → 保存矫正后 PDF 到临时文件，返回路径
  4. 无旋转时 → 返回 None（PdfProcessor 保持原路径，节省 I/O）

矫正范围：
  - 整数倍旋转（0°/90°/180°/270°）：fitz 元数据直接矫正，精确、快速
  - 亚角度偏斜（2°~15°）：政务扫描仪通常成像质量较好，无需亚角度纠正
    （后续如有需求，可在 apply() 中追加 OpenCV 霍夫变换偏斜纠正）

降级策略：
  - PyMuPDF 未安装 → ImportError 捕获，返回 None（降级使用原始文件）
  - fitz 操作异常 → Exception 捕获，返回 None（降级使用原始文件）
  降级后：OcrPipelineTransform 仍可处理未矫正文件，只是部分页可能倒置识别精度下降

激活方式（已实现）：should_apply() 改为 return opts.get("scanned", False)
"""
import os
import tempfile


class DeskewTransform:
    """
    Phase 2 实现：PDF 旋转矫正。
    仅对 ScannedDetectorTransform 判定为扫描件的 PDF 执行（scanned=True）。
    实现依赖：PyMuPDF（ai-lib/pymupdf-*.whl 已在 Phase 1 时备包）
    """

    def should_apply(self, opts: dict) -> bool:
        """
        仅对扫描件 PDF 执行旋转矫正。
        条件：opts["scanned"] = True（由 ScannedDetectorTransform 写入）
        """
        return opts.get("scanned", False)

    def apply(self, pdf_path: str, opts: dict) -> "str | None":
        """
        业务功能：矫正 PDF 各页旋转角度，返回矫正后临时文件路径。
        关键流程：
          1. fitz.open() 读取 PDF
          2. 检查是否有非零旋转页（page.rotation != 0）
          3. 无旋转页 → 直接返回 None（节省 I/O）
          4. 有旋转页 → page.set_rotation(0) 逐页矫正
          5. 保存到 NamedTemporaryFile(suffix=".pdf") 并返回路径
        降级策略：
          - PyMuPDF 不可用时返回 None，OcrPipeline 接收原始文件继续处理
          - fitz 操作异常时返回 None，同上

        :param pdf_path: 输入 PDF 路径（PageSkipTransform 裁页后的版本）
        :param opts:     共享选项字典（当前方法只读，不需要写入）
        :return:         矫正后临时 PDF 路径 | None（无旋转或处理失败时）
        """
        try:
            import fitz  # PyMuPDF，ai-lib/pymupdf-*.whl 已备（Phase 1 PageSkipTransform 依赖）

            doc = fitz.open(pdf_path)

            # 快速检测是否有非零旋转页，无旋转则直接跳过（节省临时文件 I/O）
            rotated_pages = [page for page in doc if page.rotation != 0]
            if not rotated_pages:
                doc.close()
                print(f"[DeskewTransform] ℹ️ 所有页旋转均为 0°，跳过矫正: {pdf_path}")
                return None

            rotation_summary = {page.number: page.rotation for page in rotated_pages}
            print(f"[DeskewTransform] 检测到 {len(rotated_pages)} 个旋转页: {rotation_summary}")

            # 逐页清零旋转标志（fitz 元数据矫正，不重新渲染，速度快）
            for page in doc:
                if page.rotation != 0:
                    page.set_rotation(0)

            # 保存矫正后 PDF 到临时文件
            with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
                out_path = tmp.name
            doc.save(out_path)
            doc.close()

            print(f"[DeskewTransform] ✅ 旋转矫正完成 → {out_path}")
            return out_path

        except ImportError:
            # PyMuPDF（fitz）未安装，降级跳过旋转矫正
            # Phase 1 PageSkipTransform 依赖 PyMuPDF，按预期这里不应发生
            print("⚠️ [DeskewTransform] PyMuPDF 未安装，跳过旋转矫正（OcrPipeline 将接收原始文件）")
            return None

        except Exception as e:
            print(f"⚠️ [DeskewTransform] 旋转矫正失败，使用原始文件继续: {e}")
            return None
