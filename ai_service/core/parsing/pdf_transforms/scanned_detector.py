"""
业务功能：自动探测 PDF 是否为纯图像型扫描件（Phase 2 自动触发入口）。

触发条件：对所有 PDF 均执行（should_apply 始终返回 True）。

实现策略：
  1. 用 pdfplumber 采样前 _SAMPLE_PAGES 页字符数（避免全文件遍历耗时）
  2. 总字符数 < _CHAR_THRESHOLD → 判定为扫描件，写 opts["scanned"] = True
  3. 总字符数 >= _CHAR_THRESHOLD → 判定为普通 PDF，写 opts["scanned"] = False

设计约定：
  - apply() 返回 None（只修改 opts，不产生临时文件）
  - PdfProcessor.process() 执行时 result=None → 跳过路径替换，current_path 保持不变
  - pdfplumber 已在 requirements.txt 中声明（Phase 1 BUG修复时追加），无额外依赖

探测阈值（_CHAR_THRESHOLD = 10）说明：
  - 纯图像 PDF：pdfplumber 提取字符数恒为 0
  - 含水印/数字签名文字层的"半扫描件"：可能有 < 10 个字符
  - 阈值 10 覆盖此场景，将其也走 OCR 路径（宁可多 OCR 不漏扫描件）
  - 混合型 PDF（有真实文字内容的页）：字符数 >> 10，走 pdfplumber 正常路径

变换链位置：PageSkipTransform → [本 Transform] → DeskewTransform → OcrPipelineTransform
"""


class ScannedDetectorTransform:
    """
    PDF 扫描件自动探测 Transform。
    在变换链中对所有 PDF 执行，通过 pdfplumber 字符采样判定是否需要 OCR 处理。
    探测结果写入共享 opts 字典，供后续 DeskewTransform / OcrPipelineTransform 读取。
    """

    # 扫描件判定阈值：前 _SAMPLE_PAGES 页字符总数 < 此值时判定为扫描件
    _CHAR_THRESHOLD = 10
    # 采样页数：仅取前 N 页，避免逐页遍历大文件耗费 I/O
    _SAMPLE_PAGES = 3

    def should_apply(self, opts: dict) -> bool:
        """对所有 PDF 均执行（无条件激活，探测结果写入 opts 供后续 Transform 决策）"""
        return True

    def apply(self, pdf_path: str, opts: dict) -> "str | None":
        """
        业务功能：采样 PDF 字符数，将扫描件判定结果写入 opts["scanned"]。
        关键流程：
          1. pdfplumber 打开 PDF，提取前 _SAMPLE_PAGES 页的 chars 列表
          2. 统计字符总数，与 _CHAR_THRESHOLD 比较
          3. 判定结果写入 opts["scanned"]（True=扫描件, False=普通PDF）
          4. 返回 None（不修改文件路径，PdfProcessor 保持 current_path 不变）
        降级策略：
          - pdfplumber 操作异常时不写 opts["scanned"]
          - 下游 DeskewTransform/OcrPipelineTransform 的 should_apply()=opts.get("scanned",False)=False
          - 安全降级为普通 pdfplumber 解析路径，不阻断任何文档入库流程

        :param pdf_path: 当前 PDF 文件路径（PageSkipTransform 处理后的路径）
        :param opts:     共享选项字典（引用传递，直接写入即可被后续 Transform 读取）
        :return:         始终返回 None（不产生临时文件）
        """
        if opts.get("scanned") is True or opts.get("force_ocr") is True:
            opts["scanned"] = True
            print(f"[ScannedDetector] ✅ 外部参数强制按扫描件 OCR 处理: {pdf_path}")
            return None

        try:
            import pdfplumber
            with pdfplumber.open(pdf_path) as pdf:
                total_pages = len(pdf.pages)
                # ── 方案 A：升级为“首、中、尾”分散采样，增强对混合型 PDF 的检出置信度 ──
                # 理由：防止政务扫描件自带电子封皮导致的采样“假阴性”偏见。
                if total_pages <= self._SAMPLE_PAGES:
                    sample = pdf.pages
                else:
                    # 跨度采样：确保覆盖篇头（封皮）、篇中（正文）、篇尾（附件/盖章页）
                    sample = [
                        pdf.pages[0],
                        pdf.pages[total_pages // 2],
                        pdf.pages[total_pages - 1]
                    ]
                sampled_count = len(sample)          # with 块内读取，避免关闭后访问
                char_counts = [len(p.chars) for p in sample]
                total_chars = sum(char_counts)

            low_text_pages = sum(1 for count in char_counts if count < self._CHAR_THRESHOLD)
            # 纯扫描件：采样字符总数极低。
            # 混合扫描件：如首页有文字层、正文/附件页是图片，按总和会误判普通 PDF；
            #             只要采样页中多数页缺少文字层，就进入 OCR 路径。
            is_scanned = (
                total_chars < self._CHAR_THRESHOLD
                or low_text_pages >= max(1, sampled_count - 1)
            )
            opts["scanned"] = is_scanned

            if is_scanned:
                print(
                    f"[ScannedDetector] ✅ 判定为扫描件"
                    f"（采样{sampled_count}页字符数={char_counts}, low_text_pages={low_text_pages}）: {pdf_path}"
                )
            else:
                print(
                    f"[ScannedDetector] ℹ️ 判定为普通 PDF"
                    f"（采样{sampled_count}页字符数={char_counts}, low_text_pages={low_text_pages}）: {pdf_path}"
                )

        except Exception as e:
            # 探测失败不阻断：opts["scanned"] 保持未设置，下游 Transform 安全降级
            print(f"⚠️ [ScannedDetector] 探测异常，降级为普通路径: {e}")

        # 只写 opts 标记，不产生新临时文件
        return None
