from typing import Protocol, runtime_checkable


@runtime_checkable
class AbstractParser(Protocol):
    """
    业务功能：文档解析器的抽象协议（Strategy 模式接口）。
    所有实现类必须满足：
      - can_parse(file_path): 判断是否能处理此文件（由 ParserFactory 调用）
      - parse(file_path): 将文件解析为 Markdown 文本并返回
    设计约定：
      - parse() 任何情况下都不应抛出异常，内部 try-except 自行处理
      - 解析失败时返回以【...】开头的错误描述字符串（调用方统一拦截）
      - 不负责文本净化，返回原始 Markdown 文本由 TextCleaner 处理
    """

    def can_parse(self, file_path: str) -> bool:
        """判断是否能处理指定文件（通常基于文件扩展名）"""
        ...

    def parse(self, file_path: str) -> str:
        """将文件解析为 Markdown 格式文本，失败时返回【错误描述】字符串"""
        ...
