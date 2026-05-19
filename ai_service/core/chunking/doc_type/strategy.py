"""
文档类型策略抽象层。

设计原则：
- DocTypeStrategy 是"值对象 + 策略"的组合体，既描述自己，又决定自己的分片参数。
- ChunkCfg 用 dataclass 代替裸字典，字段变更有类型检查保障。
- _count_hits 是通用计分模板，具体策略只需声明 PATTERNS，无需重复实现计分逻辑。
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass, field, asdict
from typing import List, Optional
import re


@dataclass
class ChunkCfg:
    """
    分片参数值对象（不可变）。
    字段语义：
      max_chunk_size       : 单 chunk 最大字符数，超出时按句末标点智能截断
      min_chunk_size       : 小块合并阈值，低于此值的 body chunk 向前合并
      target_chunk_size    : 长块分割目标大小（<= max_chunk_size）
      overlap_size         : 跨 chunk 语境注入的尾句字符数（0 = 禁用）
      person_boundary_words: [C2] 人员信息边界触发词列表（公示类专用）。
                             当行首命中这些词时，强制 flush 当前段落，确保
                             每位人员的公示信息独立成 chunk，实现隐私隔离。
                             其他策略保持 None（不启用该功能）。
    """
    max_chunk_size:        int = 500
    min_chunk_size:        int = 100
    target_chunk_size:     int = 350
    overlap_size:          int = 50
    # [C2] 默认 None：不影响任何已有策略；PublicNoticeDocStrategy 设置非空
    person_boundary_words: Optional[List[str]] = field(default=None, repr=False)

    def to_dict(self) -> dict:
        """转为字典，兼容下游 CoarseChunker / FineChunker 的 cfg 参数接口。"""
        return asdict(self)



class DocTypeStrategy(ABC):
    """
    文档类型策略抽象基类。

    职责边界（SRP）：
      每个子类只封装「自己是什么类型」+「自己的分片参数」+「如何识别自己」。
      子类之间完全解耦，互不感知。

    扩展方式（OCP）：
      新增文档类型 = 新建子类 + bootstrap.py 追加一行 .register()。
      不修改任何现有代码。

    未来演进路径（LSP 保证）：
      - 规则策略 → ML 分类策略：实现相同 match_score 接口，替换实现即可。
      - 静态注册 → YAML 动态加载：Registry 扩展 register_from_yaml()，无需改策略类。
    """

    @property
    @abstractmethod
    def doc_type(self) -> str:
        """类型名称，例如 '法规'、'通知'，用于日志和 ES metadata 记录。"""

    @property
    @abstractmethod
    def chunk_cfg(self) -> ChunkCfg:
        """该文档类型专属的分片参数。"""

    @abstractmethod
    def match_score(self, sample: str) -> float:
        """
        对文档采样文本（前 2000 字）计算置信分 [0.0, 1.0]。
        分数越高表示越确信该文档属于此类型。
        """

    def _count_hits(self, sample: str, patterns: List[str]) -> float:
        """
        通用计分模板：统计命中正则数量，归一化为 [0, 1] 置信分。
        子类直接复用，无需重复实现。
        """
        hits = sum(1 for p in patterns if re.search(p, sample))
        return hits / len(patterns) if patterns else 0.0
