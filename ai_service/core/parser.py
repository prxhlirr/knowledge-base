import os
import abc
import magic
import chardet
from pypdf import PdfReader
from docx import Document
import pythoncom
import win32com.client

class BaseParser(abc.ABC):
    """文档解析器抽象基类"""
    @abc.abstractmethod
    def parse(self, file_path: str) -> str:
        pass

class TxtParser(BaseParser):
    def parse(self, file_path: str) -> str:
        # 使用 chardet 读取少量字节猜测文件编码
        with open(file_path, 'rb') as f:
            raw_data = f.read(10000)
            detected = chardet.detect(raw_data)
            encoding = detected.get('encoding') or 'utf-8'

        with open(file_path, 'r', encoding=encoding, errors='ignore') as f:
            return f.read()

class DocxParser(BaseParser):
    def parse(self, file_path: str) -> str:
        doc = Document(file_path)
        return "\n".join([p.text for p in doc.paragraphs if p.text.strip()])

class DocParser(BaseParser):
    def parse(self, file_path: str) -> str:
        # doc 需要借助 COM 接口解析
        pythoncom.CoInitialize()
        try:
            word = win32com.client.DispatchEx("Word.Application")
            word.Visible = False
            doc = word.Documents.Open(os.path.abspath(file_path))
            text = doc.Content.Text
            doc.Close(False)
            word.Quit()
            return text
        finally:
            pythoncom.CoUninitialize()

class PdfParser(BaseParser):
    def parse(self, file_path: str) -> str:
        reader = PdfReader(file_path)
        text_lines = []
        for page in reader.pages:
            content = page.extract_text()
            if content:
                text_lines.append(content)
        return "\n".join(text_lines)

class MultimodalParser(BaseParser):
    """预留为后期图片/视频/音频进行多模态结构抽取的抽象入口"""
    def parse(self, file_path: str) -> str:
        raise NotImplementedError("多模态（图片/音视频）解析尚未在当前 MVP 阶段实现，后续基于 OCR 或 Whisper 进行扩展。")

class ParserFactory:
    """文档解析工厂"""
    
    _handlers = {
        ".txt": TxtParser(),
        ".docx": DocxParser(),
        ".doc": DocParser(),
        ".pdf": PdfParser(),
        # 后期扩展示例:
        # ".png": MultimodalParser(), 
        # ".mp4": MultimodalParser()
    }
    
    @classmethod
    def get_parser(cls, file_path: str) -> BaseParser:
        # 利用 python-magic 获取真实的 MIME Type 截杀伪造文件格式
        mime_type = magic.from_file(file_path, mime=True)
        
        # 建立安全可靠的白名单映射
        mime_mapping = {
            'text/plain': '.txt',
            'application/pdf': '.pdf',
            'application/msword': '.doc',
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document': '.docx'
        }
        
        target_ext = mime_mapping.get(mime_type)
        if not target_ext:
            # 兼容性兜底：部分较老的 doc 文件或特殊 txt 可能会判定异常，退化为按后缀
            target_ext = os.path.splitext(file_path)[1].lower()
            if target_ext not in mime_mapping.values():
                raise ValueError(f"安全拦截：不支持的文件类型或被伪装的格式 (MIME: {mime_type}, EXT: {target_ext})")
                
        parser = cls._handlers.get(target_ext)
        if not parser:
            raise ValueError(f"当前不支持的文件格式进行解析: {target_ext}")
        return parser

    @classmethod
    def parse_file(cls, file_path: str) -> str:
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"文件不存在: {file_path}")
            
        parser = cls.get_parser(file_path)
        return parser.parse(file_path)
