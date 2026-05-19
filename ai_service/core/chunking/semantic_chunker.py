"""
SemanticChunker — 双粒度公文语义切片组合器（集成 Strategy + Registry）

重构前：单体类，593 行，包含所有正则/标题识别/表格处理/粗粒度/细粒度逻辑
重构后→阶段三：组合器，委托 CoarseChunker + FineChunker
重构后→本次：集成 DocTypeRegistry，实现文档类型动态路由

关键改造：
  process_document() 不再使用固定 cfg，
  而是先调用 doc_type_registry.resolve() 识别文档类型，
  再以该策略的 chunk_cfg 动态构建 CoarseChunker 和 FineChunker。
  不同文体（法规/通知/报告/新闻/会议纪要）自动使用最优分片参数，
  调用方（rag_pipeline.py）零修改。

各子模块职责：
  models.py             → Chunk 数据类（跨模块共享）
  hierarchy_detector.py → HierarchyDetector（标题层级识别）
  table_processor.py    → TableProcessor（表格自然语言化，支持分批）
  coarse_chunker.py     → CoarseChunker（粗粒度三阶段流水线）
  fine_chunker.py       → FineChunker（条文切片 + 决议块识别 + 短句补偿）
  doc_type/             → DocTypeRegistry（文档类型路由，Strategy + Registry 模式）
"""
from typing import Dict, List

from core.chunking.models import Chunk
from core.chunking.coarse_chunker import CoarseChunker
from core.chunking.fine_chunker import FineChunker
from core.chunking.doc_type import doc_type_registry

# 向外暴露 Chunk，保持调用方（rag_pipeline.py）的 import 路径不变
__all__ = ["SemanticChunker", "Chunk"]


class SemanticChunker:
    """
    业务功能：双粒度公文级语义切片组合器，对外暴露统一的 process_document() 入口。
    粗粒度（coarse）：段落/章节级，100-500字，宽语义召回。
    细粒度（fine）：条文/条款/决议/短句级，15-400字，精确语义定位。

    关键改造（本次）：
      1. __init__ 保留 base_cfg（来自 model_manager.cfg），作为各策略参数的全局兜底
      2. process_document 先路由文档类型，再动态覆盖 chunk_cfg
      3. CoarseChunker / FineChunker 每次调用新建实例（无状态，线程安全）
    """

    def __init__(self, base_cfg: dict):
        # base_cfg 来自 model_manager.cfg（全局配置，含 min_quality_score 等）
        # 各策略的 ChunkCfg 字段会覆盖对应键，全局参数作为兜底
        self._base_cfg = base_cfg

    def process_document(self, markdown_text: str) -> Dict[str, List[Chunk]]:
        """
        业务功能：双粒度切片主入口，同时产出粗粒度和细粒度两套 chunks。
        关键流程：
          1. doc_type_registry.resolve()：前2000字采样，路由到最优文档类型策略
          2. merged_cfg：策略专属 chunk_cfg 覆盖全局 base_cfg（min_quality_score 等保留）
          3. CoarseChunker(merged_cfg).process()：粗粒度三阶段流水线
          4. FineChunker(merged_cfg).process()：细粒度（含决议块识别）
          5. 返回 {coarse, fine, doc_type}，doc_type 供 rag_pipeline 写入 ES metadata
        """
        # ── Step 1：文档类型路由（O(1) 复杂度，前 2000 字采样）
        strategy = doc_type_registry.resolve(markdown_text)
        doc_type = strategy.doc_type

        # ── Step 2：合成分片参数（策略专属 > 全局配置）
        merged_cfg = {**self._base_cfg, **strategy.chunk_cfg.to_dict()}

        # ── Step 3：双路切片（每次新建实例，无状态，天然线程安全）
        coarse = CoarseChunker(merged_cfg).process(markdown_text)
        fine   = FineChunker(merged_cfg).process(markdown_text)

        # ── Step 4：日志（明确记录命中类型，便于离线质量监控）
        has_articles = any(c.chunk_type == 'article' and c.quality_score == 1.0 for c in fine)
        fine_label   = "条文" if has_articles else "短句补偿"
        print(f"  [DualChunker] 类型={doc_type} | "
              f"粗粒度={len(coarse)}块 | 细粒度({fine_label})={len(fine)}块 | "
              f"max={merged_cfg['max_chunk_size']} overlap={merged_cfg['overlap_size']}")

        return {"coarse": coarse, "fine": fine, "doc_type": doc_type}
