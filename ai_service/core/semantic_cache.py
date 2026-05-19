"""
语义缓存模块（Semantic Cache）

业务功能：
    基于余弦相似度的内存级语义缓存，用于 HyDE 预热结果的复用。
    当新查询与已缓存查询的相似度 > threshold（默认 0.92）时直接返回缓存结果，
    无需重新调用 LLM 生成假设文档。

架构决策：
    为什么不用 hnswlib？
      - hnswlib 需要 C++ 编译，离线生产环境没有编译链，安装阻力大。
      - 缓存规模 ≤ 3000 条时，暴力线性扫描 numpy 矩阵运算（SIMD 加速）耗时 < 2ms。
      - 当规模 > 10000 条时 HNSW 才有明显优势，届时可平滑迁移。

    为什么不用 Redis？
      - 缓存查找在每次搜索的关键路径上，进程内访问 < 1ms，Redis 需一次网络往返 2~5ms。
      - 缓存是临时的（重启后自然重新预热），不需要跨实例共享，Redis 成本收益不合算。

性能说明：
    - 3000 条缓存：线性扫描 ≈ 0.5~2ms（numpy 矩阵向量化运算）
    - 内存占用：3000 条 × 1024 维 × 4 字节(float32) ≈ 12MB
    - 命中率目标：相似查询（同主题不同表述）命中率 > 60%
"""

import numpy as np
import threading
import time


class SemanticCache:
    """
    内存级语义缓存。

    存储结构：
        - _keys: 已缓存的原始 query 字符串列表
        - _matrix: 归一化后的向量矩阵（float32，shape=[N, 1024]），用于批量余弦相似度计算
        - _values: 对应的缓存值列表（HyDE 结果 dict）
        - _access_times: 各条目最后访问时间（LRU 淘汰用）

    线程安全：
        使用 threading.RLock 保护所有读写操作。

    淘汰策略：
        当缓存达到 max_size 时，淘汰最近最少访问（LRU）的 evict_ratio 比例条目。
    """

    def __init__(self, threshold: float = 0.92, max_size: int = 3000, evict_ratio: float = 0.2):
        """
        初始化语义缓存。

        :param threshold: 语义相似度命中阈值（cos > threshold 视为"足够相似"），默认 0.92
        :param max_size: 最大缓存条目数，超出时触发 LRU 淘汰
        :param evict_ratio: 淘汰比例（每次淘汰 max_size × evict_ratio 条最旧条目）
        """
        self.threshold = threshold
        self.max_size = max_size
        self.evict_ratio = evict_ratio

        self._lock = threading.RLock()
        self._keys: list[str] = []
        self._matrix: np.ndarray | None = None  # shape=(N, dim), float32, L2 归一化
        self._values: list[dict] = []
        self._access_times: list[float] = []

    # ──────────────────────────────────────────────────────────────────────────
    # 核心接口
    # ──────────────────────────────────────────────────────────────────────────

    def lookup(self, query_vec: list[float]) -> dict | None:
        """
        在缓存中查找语义相似的已缓存结果。

        业务流程：
          1. 将 query_vec 转为归一化 numpy 向量
          2. 对缓存矩阵做批量点积（等价于余弦相似度，因所有向量均已 L2 归一化）
          3. 找到最大相似度及其索引
          4. 相似度 > threshold 时返回缓存结果，否则返回 None

        :param query_vec: 原始 BGE-M3 dense 向量（1024 维 float 列表）
        :return: 命中的缓存 dict（含 rewritten_query 和 vector），未命中返回 None
        """
        if not query_vec:
            return None

        with self._lock:
            if self._matrix is None or len(self._keys) == 0:
                return None

            # L2 归一化后做点积 = 余弦相似度（批量矩阵运算）
            vec = self._normalize(np.array(query_vec, dtype=np.float32))
            # 矩阵点积：shape=(N,)
            sims = self._matrix @ vec

            best_idx = int(np.argmax(sims))
            best_sim = float(sims[best_idx])

            if best_sim >= self.threshold:
                self._access_times[best_idx] = time.time()
                result = self._values[best_idx]
                print(f"🎯 [SemanticCache] 命中（sim={best_sim:.4f}）key='{self._keys[best_idx][:20]}'")
                return result

        return None

    def put(self, query_vec: list[float], query_key: str, value: dict) -> None:
        """
        将 HyDE 结果写入语义缓存。

        :param query_vec: 原始 BGE-M3 dense 向量（1024 维）
        :param query_key: 对应的原始查询文本（用于日志和去重）
        :param value: 要缓存的值（HyDE 结果 dict，含 rewritten_query 和 vector）
        """
        if not query_vec or not value:
            return

        with self._lock:
            # 精确 key 去重（完全相同查询不重复写入）
            if query_key in self._keys:
                idx = self._keys.index(query_key)
                self._values[idx] = value
                self._access_times[idx] = time.time()
                return

            # 容量检查：超出时 LRU 淘汰
            if len(self._keys) >= self.max_size:
                self._evict_lru()

            # 写入新条目
            norm_vec = self._normalize(np.array(query_vec, dtype=np.float32))
            self._keys.append(query_key)
            self._values.append(value)
            self._access_times.append(time.time())

            # 重建向量矩阵（追加新行）
            if self._matrix is None:
                self._matrix = norm_vec.reshape(1, -1)
            else:
                self._matrix = np.vstack([self._matrix, norm_vec.reshape(1, -1)])

    def size(self) -> int:
        """返回当前缓存条目数。"""
        with self._lock:
            return len(self._keys)

    def clear(self) -> None:
        """清空缓存（重启预热或配置变更时使用）。"""
        with self._lock:
            self._keys.clear()
            self._values.clear()
            self._access_times.clear()
            self._matrix = None

    # ──────────────────────────────────────────────────────────────────────────
    # 私有工具方法
    # ──────────────────────────────────────────────────────────────────────────

    @staticmethod
    def _normalize(vec: np.ndarray) -> np.ndarray:
        """
        L2 归一化向量。归一化后点积 = 余弦相似度。
        :param vec: 输入向量（1D numpy array）
        :return: 归一化向量
        """
        norm = np.linalg.norm(vec)
        if norm < 1e-9:
            return vec
        return vec / norm

    def _evict_lru(self) -> None:
        """
        LRU 淘汰：按最后访问时间排序，移除最旧的 evict_ratio 比例条目。
        调用前须持锁。
        """
        evict_count = max(1, int(self.max_size * self.evict_ratio))
        # 按 access_time 升序，取前 evict_count 个索引
        sorted_indices = sorted(range(len(self._access_times)), key=lambda i: self._access_times[i])
        to_remove = set(sorted_indices[:evict_count])

        # 重建各列表（保留未淘汰的条目）
        keep_indices = [i for i in range(len(self._keys)) if i not in to_remove]
        self._keys = [self._keys[i] for i in keep_indices]
        self._values = [self._values[i] for i in keep_indices]
        self._access_times = [self._access_times[i] for i in keep_indices]
        self._matrix = self._matrix[keep_indices] if self._matrix is not None and keep_indices else None

        print(f"🗑️ [SemanticCache] LRU 淘汰 {evict_count} 条，剩余 {len(self._keys)} 条")


# ──────────────────────────────────────────────────────────────────────────────
# 全局单例（main.py 导入使用）
# ──────────────────────────────────────────────────────────────────────────────
_hyde_semantic_cache = SemanticCache(threshold=0.92, max_size=3000)
