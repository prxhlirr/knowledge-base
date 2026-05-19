# 对外暴露最小接口，调用方只需：
# from core.chunking.doc_type import doc_type_registry
from .registry import DocTypeRegistry
from .bootstrap import doc_type_registry

__all__ = ["DocTypeRegistry", "doc_type_registry"]
