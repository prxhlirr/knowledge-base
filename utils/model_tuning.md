# AI 模型推理性能调优记录 (Model Tuning)

本文档归档了在开发与压测 `ai_service` 时，针对文本向量化与交叉重排（Cross-Encoder）接口的长耗时问题所采用的一系列调优策略。此经验对于无 GPU 加速的本地开发服务器尤为重要。

## 1. 现象与瓶颈

在接入 BGE-m3 和 BGE-reranker-v2-m3 后，一次长文本的检索重排（Hybrid Search 中提取 top k）遇到了长达 **18s+** 的响应延迟：
1. **冷启动加载 (约 9s)**：尽管模型实例在启动时初始化，Pytorch 及 SentenceTransformer 在首次发起真实前向推理 (Forward Pass) 时，仍需巨量的时间进行 JIT 图分配及显存页整理。
2. **CPU 平方级复杂度崩溃 (约 9s)**：Cross-Encoder 的底层自注意力计算随文档长度 $L$ 呈现 $O(L^2)$ 的几何级数增长。对于 10 篇乃至 50 篇长文档，未限制长度导致的计算负载彻底拖垮 CPU 性能。

## 2. 调优策略与实现路径

### 2.1 保活预热机制 (Warmup)
为避免前端用户的首次搜索遭遇断崖式体验掉线，我们在 `core/model_manager.py` 初始化完毕后注入了系统级热身指令，主动向两个模型丢入 Dummy (测试) 数据触发首次图计算：
```python
self.encode("warmup", batch_size=1)
self.rerank("warmup", ["warmup"])
```

### 2.2 线程绑定优化 (Thread Pinning)
在 CPU 环境下，PyTorch 默认会“贪婪”占满所有物理/逻辑核。当执行细粒度文本矩阵计算时，过多的上下文线程切换反而会导致性能暴跌。我们介入设定了单步推理的硬约束：
```python
if self.device == "cpu":
    threads = max(1, min(4, os.cpu_count() or 4))
    torch.set_num_threads(threads)
```

### 2.3 动态序列截断 (Dynamic Sequence Truncation)
根据政务或报告类长文本的特征，绝绝大多数包含核心 Query 命中率的实体词与意图分布在文章初段或摘要中。因此限制 `max_length` 可获得以**极小的召回损失换取百倍加速**的效果。
```python
# GPU 算力足够时保留更多的下文（512），而 CPU 被强制截断到 128 Token 核心区
rerank_len = 512 if self.device == "cuda" else 128
self.reranker = CrossEncoder(reranker_path, device=self.device, max_length=rerank_len)
```

## 3. 下一步规划：结合管控后台动态化

根据 `requirements/管控后台.md` (原子功能 I1：检索权重热调整面板) 的规划要求，我们绝不应该把上述的 `max_length`, `threads` 写死在代码里。完整的调参闭环应当为：

**功能设计思路**：
1. **统一的配置存储**：在 MySQL (或基于 Redis 提供的高速缓存) 中建立一张 `sys_ai_tuning_config` 键值表。
2. **管控界面 (UI)**：由前端 Vue3 提供一个滑块或数值面板，允许管理员根据当前硬件负载实时热更改以下参数：
    - `vector_batch_size` (向量化默认批次)
    - `rerank_max_length_cpu` (CPU环境精排截断深度)
    - `rerank_max_length_gpu` (GPU环境精排截断深度)
    - `es_bm25_weight` vs `es_knn_weight` (混合检索 ES 打分权重比)
3. **推拉结合热加载**：
    - Python 端可以暴露一个内部轮询心跳或 `@app.post("/api/ai/config/reload")` Endpoint。
    - 每当后台管理员点“应用调优”时，由 Java 层将新的超参广播给 Python，`ModelManager` 予以热更新 (即刻更改 `self.reranker.max_length` 等) 而无需重启容器服务。
