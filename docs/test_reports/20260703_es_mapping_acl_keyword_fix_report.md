# ES Mapping 与 QA ACL 兼容修复测试报告

## 一、修复范围

本轮只修复角色权限进入生产前的两个阻断点：

1. QA 检索兼容历史 `acl_tokens` 动态映射。
   - 当前 `kb_qa_pairs.acl_tokens` 实际为 `text + keyword`。
   - 原 QA 查询只查 `acl_tokens`，`terms` 精确过滤命中 0。
   - 修复后同时查 `acl_tokens` 与 `acl_tokens.keyword`。

2. 新索引模板显式声明权限字段。
   - `kb_document_template` 顶层补齐 `acl_tokens/source_index/index_code/owner_unit_code/visible_unit_codes/permission_version`。
   - `metadata` 下同步补齐权限字段。
   - `kb_qa_pairs` 新建 mapping 补齐 `acl_tokens` 及权限投影字段。
   - `rag_pipeline.py` 中重复的旧模板入口同步补齐，避免启动顺序覆盖新模板。

## 二、涉及文件

- `ai_service/main.py`
- `ai_service/core/indexing/es_setup.py`
- `ai_service/core/rag_pipeline.py`
- `ai_service/tools_and_tests/test_qa_source_index_filter.py`
- `ai_service/tools_and_tests/test_es_setup_index_settings.py`

## 三、验证结果

### 1. Python 语法检查

命令：

```powershell
python -m py_compile ai_service\main.py ai_service\core\indexing\es_setup.py ai_service\core\rag_pipeline.py
```

结果：通过。

### 2. QA 权限过滤测试

命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_qa_source_index_filter.py
```

结果：11 个用例全部通过。

覆盖点：

- `acl_tokens` 与 `acl_tokens.keyword` 双字段兼容。
- `_SUPER_ADMIN` 旁路不变。
- token 缺失时 fail-closed，只允许无 ACL 字段历史数据。
- QA KNN 与 BM25 两条路径复用统一 ACL filter。

### 3. ES setup mapping 测试

命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_es_setup_index_settings.py
```

结果：7 个用例全部通过。

覆盖点：

- 文档索引模板权限字段为 `keyword/long`。
- 直接创建 `kb_document_v1` 时 `acl_tokens` 为 `keyword`。
- 新建 `kb_qa_pairs` 时 `acl_tokens` 为 `keyword`。
- `rag_pipeline.py` 重复模板入口保留权限字段。

### 4. 真实 ES 只读/模板验证

当前 `kb_qa_pairs` 验证：

```text
terms acl_tokens          -> 0
terms acl_tokens.keyword  -> 419
```

说明本轮 QA 兼容修复是必要的。

当前 `kb_document_template` 已更新，读回关键字段：

```text
acl_tokens                  keyword
source_index                keyword
visible_unit_codes          keyword
permission_version          long
metadata.acl_tokens         keyword
metadata.source_index       keyword
metadata.visible_unit_codes keyword
metadata.permission_version long
```

## 四、遗留风险

现有历史索引中已存在的字段类型不能原地从 `text + keyword` 改成纯 `keyword`。因此：

- `kb_qa_pairs` 需要后续新建 `kb_qa_pairs_v2` 并 reindex。
- 已存在的 `kb_document_*` 如顶层 `acl_tokens` 已动态映射为 text，也需要在下一轮迁移到新物理索引。
- 本轮已修复查询兼容和未来模板，尚未执行历史索引重建迁移。

## 五、下一步建议

1. 创建 `kb_qa_pairs_v2`，使用正确 mapping。
2. 将 `kb_qa_pairs` reindex 到 v2。
3. 原子切换 `kb_qa_read/kb_qa_write`。
4. 对 `kb_document_*` 分业务索引逐个做 v2 reindex，修正顶层权限字段类型。
5. 切换 `kb_document` 读别名并保留旧索引用于回滚窗口。
