# 知识库升级 v2.0 过渡版 - 旧脚本 RAGPipeline KNN 候选数配置化测试报告

## 任务目标

治理 `ai_service/scripts/rag_pipeline.py` 旧脚本副本中的 `num_candidates=100` 固定值问题。

该脚本不是生产 Worker 的主要入口，但仍可能被人工运行。为避免调试入口和生产入口行为不一致，本轮将其与 `ai_service/core/rag_pipeline.py` 对齐。

## 本轮改动

1. `ai_service/scripts/rag_pipeline.py`
   - 新增 `local_search_knn_num_candidates(top_k)`。
   - 复用环境变量 `RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES`。
   - 默认候选数为 `200`。
   - 非法环境变量回退 `200`。
   - 实际候选数保证不小于 `top_k` 和 `top_k * 2`。
   - `RAGPipeline.search()` 的 KNN DSL 改为调用该函数。
   - 删除 `return` 后不可达的重复 KNN 查询代码。

2. `ai_service/tools_and_tests/test_scripts_rag_pipeline_knn_candidates.py`
   - 使用 AST 提取函数，不 import 旧脚本，避免加载模型、torch 或连接 ES。
   - 验证默认值。
   - 验证环境变量覆盖。
   - 验证非法环境变量兜底。
   - 验证旧脚本 `RAGPipeline.search()` 不再使用字面量 `100`。

## 测试命令

```bash
python -m py_compile ai_service/scripts/rag_pipeline.py ai_service/tools_and_tests/test_scripts_rag_pipeline_knn_candidates.py
python ai_service/tools_and_tests/test_scripts_rag_pipeline_knn_candidates.py
```

补充扫描：

```bash
rg -n 'num_candidates.: 50|num_candidates.: 100|RAG_LOCAL_SEARCH_KNN_NUM_CANDIDATES|local_search_knn_num_candidates' ai_service/main.py ai_service/core/rag_pipeline.py ai_service/scripts/rag_pipeline.py ai_service/tools_and_tests/test_scripts_rag_pipeline_knn_candidates.py
```

## 测试结果

```text
PASS test_script_local_search_candidates_uses_safe_default_and_topk_floor
PASS test_script_local_search_candidates_accepts_operational_override
PASS test_script_local_search_candidates_falls_back_for_invalid_env
PASS test_script_search_uses_candidate_helper_not_literal_100
```

## 验证结论

1. `ai_service/scripts/rag_pipeline.py` 已不再固定使用 `num_candidates=100`。
2. 旧脚本与生产 `core/rag_pipeline.py` 使用同一个环境变量语义。
3. Python 侧已扫描不到在线 QA 和 RAGPipeline 本地搜索中的 `num_candidates=50/100` 固定值。
4. 本轮不影响生产入库主流程，不改变 ES mapping。

## 剩余风险

1. 旧脚本仍存在其他历史硬编码，例如固定 `INDEX_NAME="kb_document_v1"`、固定 `QA_INDEX_NAME="kb_qa_pairs"`，后续若继续保留该脚本，应单独做脚本入口治理。
2. 候选数配置化只能治理召回窗口，不解决向量存储量化、索引拆分、冷热分层等更大的亿级检索问题。
