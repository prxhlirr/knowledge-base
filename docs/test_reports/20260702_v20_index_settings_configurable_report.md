# 知识库升级 v2.0 过渡版 - ES 索引分片副本配置化测试报告

## 任务目标

修复亿级数据风险中的第一项：`kb_document_*`、`kb_doc_meta`、`kb_doc_search` 新建索引时分片和副本固定为 `shards=1, replicas=0`。

本轮只处理“新索引/新模板可配置”，不直接修改历史索引。原因是 ES 主分片数不能对已有索引原地修改，历史数据必须通过新索引、reindex、alias 切换完成迁移。

## 本轮改动

1. `ai_service/core/indexing/es_setup.py`
   - 新增 `_env_int(...)`，统一读取整数环境变量。
   - 新增 `document_index_settings()`：
     - `KB_DOCUMENT_SHARDS`，默认 `1`
     - `KB_DOCUMENT_REPLICAS`，默认 `0`
   - 新增 `doc_meta_index_settings()`：
     - `KB_DOC_META_SHARDS`，默认 `1`
     - `KB_DOC_META_REPLICAS`，默认 `0`
   - 新增 `doc_search_index_settings()`：
     - `KB_DOC_SEARCH_SHARDS`，默认 `1`
     - `KB_DOC_SEARCH_REPLICAS`，默认 `0`
     - `KB_DOC_SEARCH_MAX_NGRAM_DIFF`，默认 `6`
   - `doc_meta_index_mapping()` 改为使用 `doc_meta_index_settings()`。
   - `doc_search_index_mapping()` 改为使用 `doc_search_index_settings()`。
   - 默认 `kb_document_v1` 创建逻辑改为使用 `document_index_settings()`。
   - `kb_document_template` 模板改为使用 `document_index_settings()`。
   - 补齐 `source_index/index_code/owner_unit_code/visible_unit_codes/permission_version` 在 `kb_document_*`、`kb_doc_meta`、`kb_doc_search`、`kb_qa_pairs` 的显式 mapping，避免动态 mapping 推断成低效 text 字段。
   - `migrate` 模式中为已有主 chunk 索引追加同一组权限投影字段。

2. `ai_service/tools_and_tests/test_es_setup_index_settings.py`
   - 使用标准库 `unittest`，不依赖 pytest。
   - 验证默认单节点配置保持不变。
   - 验证生产扩容环境变量可生效。
   - 验证非法配置会回退安全默认值。
   - 使用 fake ES 捕获 `kb_document_template`，验证模板 settings 确实使用可配置值。
   - 验证权限投影字段在辅助索引和 chunk 模板中是 `keyword/long`。

## 测试命令

```bash
python -m py_compile ai_service/core/indexing/es_setup.py ai_service/tools_and_tests/test_es_setup_index_settings.py
python -m unittest ai_service.tools_and_tests.test_es_setup_index_settings
```

## 测试结果

```text
Ran 5 tests in 0.001s
OK
```

## 验证结论

1. 默认配置仍是 `shards=1, replicas=0`，兼容当前单节点环境。
2. 新建 `kb_document_*` 业务索引和模板可以通过环境变量调整分片/副本。
3. 新建 `kb_doc_meta` 可以独立调整分片/副本。
4. 新建 `kb_doc_search` 可以独立调整分片/副本，并保留 ngram analyzer 配置。
5. 非法环境变量不会生成非法索引配置。
6. 权限投影字段不会再依赖 ES 动态 mapping 推断，后续 terms/filter 的性能和语义更稳定。

## 生产使用建议

过渡版生产扩容示例：

```bash
KB_DOCUMENT_SHARDS=12
KB_DOCUMENT_REPLICAS=1
KB_DOC_META_SHARDS=6
KB_DOC_META_REPLICAS=1
KB_DOC_SEARCH_SHARDS=4
KB_DOC_SEARCH_REPLICAS=1
```

这些配置只会影响新建索引或新模板。历史索引仍需通过新物理索引、reindex、alias 切换完成迁移。

## 剩余风险

1. 历史索引主分片数不会自动改变，必须做 reindex。
2. 本轮没有处理向量量化、KNN `num_candidates` 自适应、rerank 吞吐、冷热节点路由等问题。
3. 已有历史数据若缺少 `source_index/visible_unit_codes` 等权限投影字段，仍需执行历史补齐任务。
