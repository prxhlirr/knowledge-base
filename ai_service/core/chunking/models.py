from dataclasses import dataclass, field


@dataclass
class Chunk:
    """
    业务功能：统一分片实体，贯穿切片、向量化、入库全链路。
    字段说明：
      - content：展示版内容（含面包屑/overlap 前缀），用于全文检索和前端展示
      - raw_content：纯正文快照（无面包屑/overlap），专用于向量化，避免面包屑污染向量空间（P0-5A 修复）
      - chunk_type：'title'|'body'|'table'|'doc_num'|'article'
      - section_path：篇章路径，例如 "一/(一)/1." 或 "第十四条"
      - quality_score：质量分，< min_quality_score 时被过滤（标点密度过高等噪声块）
    """
    content: str
    raw_content: str = ""      # [P0-5A] 纯正文（不含面包屑/overlap），专用于向量化
    chunk_type: str = "body"   # 'title', 'body', 'table', 'doc_num', 'article'
    section_path: str = ""     # 篇章路径，例如 "一/(一)/1" 或 "第十四条"
    quality_score: float = 1.0
    # [Phase3] 前言标记：文档开篇套话块（"根据/按照/现将/特此"等引言段落）
    # 写入 ES metadata.is_preamble=True，RrfFusionStep 据此额外降权 50%。
    # 目的：避免前言语义向量与文档主题词高度相似导致的"伪相关排名"问题。
    is_preamble: bool = False
