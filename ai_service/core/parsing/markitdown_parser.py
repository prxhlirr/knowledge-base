class MarkItDownParser:
    """
    业务功能：通用兜底解析器，使用 MarkItDown 处理 PDF/TXT 及其他格式。
    设计决策：
      - 作为 ParserFactory 中优先级最低的 Parser，当所有专属 Parser 均无法处理时触发
      - 始终返回非空字符串（成功返回内容，失败返回【错误描述】）
      - 不设置 can_parse 过滤条件（兜底角色），由 ParserFactory 在最后调用
    """

    def __init__(self, md_converter):
        # 接受外部注入的 MarkItDown 实例（避免在每次 parse 时重建，节省初始化开销）
        self._md = md_converter

    def can_parse(self, file_path: str) -> bool:
        # 兜底 Parser 永远返回 True，由 ParserFactory 保证仅在其他 Parser 失败后调用
        return True

    def parse(self, file_path: str) -> str:
        """使用 MarkItDown 解析任意格式文件，失败时返回带【】前缀的错误描述字符串"""
        try:
            result = self._md.convert(file_path)
            return result.text_content
        except Exception as _err:
            print(f"⚠️ [MarkItDownParser] 底层解析引擎拦截到异常: {_err}")
            return (f"【内容提取受限】：文件流受损或遇到无法解析的加密拦截"
                    f"（报错提示：{str(_err)}）。系统已安全跳过正文抽取，"
                    f"您可以尝试修复受损文件后重新覆盖上传。")
