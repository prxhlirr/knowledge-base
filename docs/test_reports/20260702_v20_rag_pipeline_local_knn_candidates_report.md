# 知识库升级 v2.0 过渡版 - RAGPipeline 本地语义搜索 KNN 候选数配置化测试报告

## 任务目标

治理生产 `RAGPipeline.search()` 中 `num_candidates=100` 固定值问题。

本轮只修改生产 Worker 使用的 `ai_service/core/rag_pipeline.py`，不修改 `ai_service/scripts/rag_pipeline.py` 旧脚本副本。原因是本任务按小步实施控制在 3 个文件内，旧脚本副本下一轮单独处理。

## 本轮改动

1. `ai_service/core/rag_pipeline.py`
   - 新增 `local_search_knn_num_candidates(top_k)`。
   - 新增环境变量 `RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES`。
   - 默认候选数从固定 `100` 调整为 `200`。
   - 非法环境变量回退 `200`。
   - 实际候选数保证不小于 `top_k` 和 `top_k * 2`。
   - `RAGPipeline.search()` 的 KNN DSL 改为调用该函数。
   - 删除 `return` 后不可达的重复 KNN 查询代码。

2. `ai_service/tools_and_tests/test_rag_pipeline_knn_candidates.py`
   - 使用 AST 提取函数，不 import 完整 RAGPipeline，避免连接 ES 或加载模型。
   - 验证默认值。
   - 验证环境变量覆盖。
   - 验证非法环境变量兜底。
   - 验证 `RAGPipeline.search()` 不再使用字面量 `100`。

## 测试命令

```bash
python -m py_compile ai_service/core/rag_pipeline.py ai_service/tools_and_tests/test_rag_pipeline_knn_candidates.py
python ai_service/tools_and_tests/test_rag_pipeline_knn_candidates.py
```

补充扫描：

```bash
rg -n 'num_candidates.: 100|RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES|local_search_knn_num_candidates|num_candidates' ai_service/core/rag_pipeline.py ai_service/scripts/rag_pipeline.py ai_service/tools_and_tests/test_rag_pipeline_knn_candidates.py
```

## 测试结果

```text
PASS test_local_search_candidates_uses_safe_default_and_topk_floor
PASS test_local_search_candidates_accepts_operational_override
PASS test_local_search_candidates_falls_back_for_invalid_env
PASS test_rag_pipeline_search_uses_candidate_helper_not_literal_100
```

## 验证结论

1. 生产 `ai_service/core/rag_pipeline.py` 已不再固定使用 `num_candidates=100`。
2. 本地调试语义搜索可通过 `RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES` 调整候选窗口。
3. 不可达重复 KNN 查询代码已清理，降低维护误判风险。
4. 本轮不影响主检索 SearchServiceV2，也不改变入库逻辑和 ES mapping。

## 剩余风险

1. `ai_service/scripts/rag_pipeline.py` 旧脚本副本仍有两个 `num_candidates=100`。
2. 该旧脚本如果仍被人工运行，仍可能使用固定候选数；建议下一轮将其与生产 `core/rag_pipeline.py` 对齐，或明确废弃。
