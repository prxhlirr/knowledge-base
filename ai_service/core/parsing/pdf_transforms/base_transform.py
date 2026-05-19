"""
业务功能：PDF 变换器基础协议（Strategy 模式接口）。
关键约定：
  所有 Transform 实现类必须满足：
    - should_apply(opts): 根据调用选项判断此变换是否需要激活
    - apply(pdf_path, opts): 执行变换，返回处理后的新 PDF 路径（临时文件路径），
        失败或无需变换时返回 None（调用方回退使用原始路径，不阻断流程）
设计约定：
  - apply() 内部 try-except 自行处理异常，降级返回 None，不向上抛出
  - 每次 apply() 生成独立的 NamedTemporaryFile，调用方（PdfProcessor）负责 finally 清理
  - 变换实现类应是无状态的，同一实例可并发调用（线程安全）
  - should_apply 中硬编码 False 的 stub 在 Phase 2 实现时改为实际条件
"""
from typing import Protocol, runtime_checkable


@runtime_checkable
class BaseTransform(Protocol):
    """
    PDF 变换器协议接口（Strategy Pattern）。
    实现者：PageSkipTransform / DeskewTransform / OcrPipelineTransform
    调用者：PdfProcessor（负责按顺序编排）
    """

    def should_apply(self, opts: dict) -> bool:
        """
        判断是否需要激活此变换。
        入参：opts - parse_options 字典，来自 RAGPipeline 读取的 ext_metadata
        返回：True = 执行变换；False = 跳过，当前文件保持不变
        """
        ...

    def apply(self, pdf_path: str, opts: dict) -> "str | None":
        """
        执行 PDF 变换，返回处理后新文件的路径（临时文件）。
        入参：
          pdf_path - 当前 PDF 路径（可能是上一个 Transform 的临时文件）
          opts     - parse_options 字典
        返回：
          成功 → 新临时文件路径（调用方负责清理）
          失败或无变化 → None（调用方继续使用 pdf_path）
        """
        ...
