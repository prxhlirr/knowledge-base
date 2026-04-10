import math
import logging
from typing import List, Dict

logger = logging.getLogger(__name__)

class LTRManager:
    """
    Learning-to-Rank (LTR) Manager - 负责知识库检索最后一环的排序打分。
    提供平滑过度的 '合成权重' (Synthetic Baseline) 初始化环境，
    将 Java 中硬编码的多路召回权重组合公式提取到 AI 服务层统一管理，
    为将来引入基于点击反馈训练的 XGBoost/LightGBM 等树模型做接口铺垫。
    """
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(LTRManager, cls).__new__(cls)
            cls._instance.model = None
            logger.info("🚀 [LTRManager] Initialized with Synthetic Baseline Weights")
        return cls._instance
        
    def predict_score(self, features: Dict) -> float:
        """
        针对单个文档块特征进行最终预估打分。
        支持特征：
        - qa_hit: 是否命中问答直通通道
        - rerank_score: 交叉编码或 ColBERT 大模型重排分 (-无穷~+无穷)
        - is_colbert: 是否为 ColBERT 打分
        - normalized_es_score: 归一化后的 Elasticsearch 分数 [0, 1)
        - raw_es_score: 原生 ES 词频命中分
        """
        qa_hit = features.get("qa_hit", False)
        if qa_hit:
            return 0.95
            
        normalized_es_score = features.get("normalized_es_score", 0.0)
        raw_es_score = features.get("raw_es_score", 0.0)
        rerank_score = features.get("rerank_score", None)
        
        if rerank_score is not None:
            is_colbert = features.get("is_colbert", False)
            if not is_colbert:
                # 如果没显式指定，可以通过分数分布特征来启发式判断（暂时兼容）
                is_colbert = (0.0 <= rerank_score <= 1.0)
                
            # 大模型分数清洗 (ColBERT 是 MaxSim，BGE 是 logits 需要 sigmoid)
            relevance_score = rerank_score if is_colbert else 1.0 / (1.0 + math.exp(-rerank_score))
            
            # [阈值修正] 由于 ColBERT 的底层是 BERT Embedding，未对齐空间的两个无关句子
            # 其 Token 两两 MaxSim 也会碰撞出 0.50 ~ 0.58 的底噪高分。
            # 因此，只有 > 0.60 才能算是真正有哪怕一丝丝语义关联，低于此值视为纯幻觉。
            veto_threshold = 0.60 if is_colbert else 0.10
            
            # Veto 拦截：大模型认为完全无关 (低于阈值)，直接赋值极低分
            if relevance_score < veto_threshold:
                return 0.0001
            else:
                # 基准线性权重公式：90% Rerank + 10% BM25
                return (relevance_score * 0.9) + (normalized_es_score * 0.1)
        else:
            # 降级模式：Reranker 因超时/熔断未能打分时的保底逻辑
            # [Hubness Fix] 原条件 `knn_score > 0.35 or rrf_score > 0.01` 存在致命缺陷：
            #   - BGE-M3 在稠密向量空间的 Hubness 底噪区间为 0.45~0.55，`knn>0.35` 几乎全量通过
            #   - RRF 分 1/(60+rank)，KNN top-50 内的所有文档都 > 0.01，等于没有过滤
            # 结果：Reranker 超时（CPU 重排 5 文档需 1-2s，原 800ms 超时必然触发）后，
            #       纯向量底噪（如"主要任务"匹配"愁"得分 0.47）会直达用户界面。
            # 根治：降级模式下只信任两类信号：
            #   1. BM25 有实际词频命中（raw_es_score > 1.0）→ 字面匹配，可信度高
            #   2. KNN 高度相似（>= 0.70）→ 远超底噪区间，语义确实高度相近
            knn_score = features.get("knn_score", 0.0)

            if raw_es_score > 1.0:
                # BM25 命中：以 ES 分 + RRF 分综合打分，不依赖 KNN 底噪
                rrf_score = features.get("rrf_score", 0.0)
                return max(normalized_es_score, rrf_score * 5.0)
            elif knn_score >= 0.65:
                # [Hubness Fix] 阈值 0.65（原 0.70 → 下调）：
                #   - 底噪区间  0.45~0.55：仍被完全拦截 ✓
                #   - 近义词语义（如"月华"→"月光"）knn ≈ 0.65~0.70：可通过 ✓
                #   - 分值乘以 0.70 保守打分，避免无重排保证时过度自信
                return knn_score * 0.70
            else:
                # 纯向量底噪 / 无任何可信信号：赋予拦截底分
                return 0.0001

    def rank(self, candidates_features: List[Dict]) -> List[float]:
        """
        批量处理特征预测。
        """
        if self.model is not None:
            # TODO: 未来加载 Scikit-Learn RandomForest 或者 XGBoost 时，可在此展开为 DataFrame predict
            pass
            
        scores = []
        for feat in candidates_features:
            scores.append(self.predict_score(feat))
            
        return scores
