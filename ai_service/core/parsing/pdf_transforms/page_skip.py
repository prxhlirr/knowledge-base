"""
业务功能：Phase 1 实现 —— 使用 PyMuPDF 物理删除 PDF 前 N 页。
触发条件：parse_options["skip_pages"] > 0
关键流程：
  1. 读取 opts["skip_pages"]（整数，由 Java 端 skipFirstPage=true 转换为 1）
  2. fitz.open() 打开原始 PDF，读取总页数
  3. 校验 skip_pages < total_pages（若 >= 则裁完无内容，打印警告并返回 None）
  4. delete_pages(0, skip_pages-1) 物理删除前 N 页（fitz 含头含尾区间）
  5. 保存至 NamedTemporaryFile（suffix=.pdf），返回临时文件路径
降级策略：
  - PyMuPDF 未安装（容器内未包含 ai-lib/pymupdf-*.whl）→ ImportError 捕获，返回 None
  - fitz 操作异常（文件损坏等）→ Exception 捕获，返回 None
  降级后：PdfProcessor 使用原始文件继续，首页内容会被包含（日志打印警告）
并发安全：NamedTemporaryFile 每次生成唯一路径，无竞争条件
"""
import os
import tempfile


class PageSkipTransform:
    """
    Phase 1 Transform：物理裁去 PDF 前 N 页（适用于跳过扫描件红头首页）。
    实现依赖：PyMuPDF（ai-lib/pymupdf-*.whl 已离线备包）
    """

    def should_apply(self, opts: dict) -> bool:
        """当 skip_pages > 0 时激活此变换"""
        return int(opts.get("skip_pages", 0)) > 0

    def apply(self, pdf_path: str, opts: dict) -> "str | None":
        """
        物理删除 PDF 前 skip_pages 页，返回裁页后的临时 PDF 路径。
        失败时返回 None，调用方回退使用原始文件。
        """
        skip_pages = int(opts.get("skip_pages", 0))
        try:
            import fitz  # PyMuPDF，ai-lib/pymupdf-*.whl 已备

            doc = fitz.open(pdf_path)
            total = doc.page_count

            # 裁页后须有剩余内容，否则无意义（后续净化门控会拒绝空文档）
            if skip_pages >= total:
                print(f"⚠️ [PageSkipTransform] skip_pages={skip_pages} >= 总页数={total}，"
                      f"裁页后无内容，跳过变换（后续触发 empty_after_cleaning）")
                doc.close()
                return None

            # fitz.delete_pages 区间含头含尾，索引从 0 开始
            doc.delete_pages(0, skip_pages - 1)

            with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
                out_path = tmp.name
            doc.save(out_path)
            doc.close()

            print(f"[PageSkipTransform] 裁页完成：跳过 {skip_pages} 页，"
                  f"剩余 {total - skip_pages} 页 → {out_path}")
            return out_path

        except ImportError:
            # PyMuPDF 未安装时降级，打印警告使运维可发现（ai-lib 中应有 pymupdf-*.whl）
            print("⚠️ [PageSkipTransform] PyMuPDF 未安装，跳过裁页。"
                  "请确认 ai-lib/pymupdf-*.whl 已包含在构建包中")
            return None
        except Exception as e:
            print(f"⚠️ [PageSkipTransform] 裁页失败，使用原始文件继续: {e}")
            return None
