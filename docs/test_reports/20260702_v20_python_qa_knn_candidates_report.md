# 知识库升级 v2.0 过渡版 - Python 在线 QA KNN 候选数配置化测试报告

## 任务目标

修复 Python 在线 QA 检索入口 `/api/ai/qa/search` 中 `num_candidates=50` 固定值问题。

本轮只处理在线 QA 检索接口，不处理 `ai_service/core/rag_pipeline.py` 与 `ai_service/scripts/rag_pipeline.py` 中长文 QA 生成链路的 `num_candidates=100`。

## 本轮改动

1. `ai_service/main.py`
   - 新增 `_qa_knn_num_candidates(top_k)`。
   - 新增环境变量 `QA_KNN_NUM_CANDIDATES`。
   - 默认候选数从固定 `50` 调整为 `100`。
   - 非法环境变量回退 `100`。
   - 实际候选数保证不小于 `top_k` 和 `top_k * 2`。
   - `/api/ai/qa/search` 的 KNN DSL 改为：
     - `num_candidates: _qa_knn_num_candidates(req.top_k)`

2. `ai_service/tools_and_tests/test_qa_source_index_filter.py`
   - 复用现有 AST 测试方式，不 import 完整 FastAPI 应用。
   - 新增默认值测试。
   - 新增环境变量覆盖测试。
   - 新增非法环境变量兜底测试。
   - 新增在线 QA KNN DSL 不再使用字面量 `50` 的结构测试。

## 测试命令

```bash
python -m py_compile ai_service/main.py ai_service/tools_and_tests/test_qa_source_index_filter.py
python ai_service/tools_and_tests/test_qa_source_index_filter.py
```

补充扫描：

```bash
rg -n 'num_candidates.: 50|QA_KNN_NUM_CANDIDATES|_qa_knn_num_candidates|num_candidates' ai_service/main.py ai_service/tools_and_tests/test_qa_source_index_filter.py
rg -n 'num_candidates.: 50|num_candidates.: 100' ai_service/main.py ai_service/core/rag_pipeline.py ai_service/scripts/rag_pipeline.py
```

## 测试结果

```text
PASS test_filter_uses_source_index_and_keyword_variant
PASS test_filter_ignores_alias_or_wildcard_to_avoid_guessing
PASS test_filter_ignores_non_document_indexes
PASS test_qa_response_source_projection_includes_permission_fields
PASS test_qa_knn_candidates_uses_safe_default_and_topk_floor
PASS test_qa_knn_candidates_accepts_operational_override
PASS test_qa_knn_candidates_falls_back_for_invalid_env
PASS test_qa_knn_query_uses_candidate_helper_not_literal_50
```

## 验证结论

1. 在线 QA KNN 已从固定 `50` 改为可配置。
2. 默认值为 `100`，比旧值更适合 v2.0 过渡版召回。
3. 运维可通过 `QA_KNN_NUM_CANDIDATES=200/300` 调整 QA 召回窗口，无需重新发布。
4. 当 `top_k` 变大时，候选数会自动抬升，避免 ES KNN 参数不合理。

## 剩余风险

1. `ai_service/core/rag_pipeline.py` 中仍有两个 `num_candidates=100`。
2. `ai_service/scripts/rag_pipeline.py` 中仍有两个 `num_candidates=100`。
3. QA 候选数升高会增加 QA 索引查询成本，生产应结合 QA 索引规模、ES latency、接口 QPS 压测后确定最终值。
