# P1 权限投影严格模式就绪审计测试报告

## 一、任务目标

本任务新增关闭历史兼容开关前的只读审计能力。

第一性原理判断：

- 严格模式的前提是 ES 中参与检索的最新数据都具备完整权限投影字段。
- 如果不先统计历史缺字段数量，直接关闭兼容开关会造成漏召回。
- 因此需要一个可重复执行、可返回非 0 状态码的审计脚本。

## 二、代码变更

新增脚本：

```text
ai_service/scripts/audit_permission_projection_readiness.py
```

审计索引：

```text
kb_document_*
kb_doc_search
kb_doc_meta
kb_qa_*
```

审计字段：

```text
acl_tokens
source_index
visible_unit_codes
```

可配置环境变量：

```text
ES_HOST
ES_USER
ES_PASS
SOURCE_INDEX
KB_DOC_SEARCH_READ_ALIAS
KB_DOC_META_READ_ALIAS
QA_INDEX_PATTERN
```

可选参数：

```text
--fail-on-risk
```

存在缺字段或审计异常时，返回退出码 `2`。

## 三、测试用例

新增测试：

```text
ai_service/tools_and_tests/test_audit_permission_projection_readiness.py
```

覆盖内容：

1. `missing_field_query` 会同时包含 latest 过滤和 missing exists 条件。
2. chunk 索引使用 `metadata.is_latest`，辅助索引使用顶层 `is_latest`。
3. `has_risk` 对缺字段和未知结果都按风险处理。

## 四、执行命令

```powershell
python -m py_compile ai_service\scripts\audit_permission_projection_readiness.py ai_service\tools_and_tests\test_audit_permission_projection_readiness.py
python ai_service\tools_and_tests\test_audit_permission_projection_readiness.py
python ai_service\scripts\audit_permission_projection_readiness.py
```

## 五、测试结果

```text
PASS permission projection readiness audit tests
```

实际 ES 审计结果：

```json
{
  "chunk": {
    "index": "kb_document_*",
    "latestField": "metadata.is_latest",
    "missing": {
      "acl_tokens": 399,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  },
  "doc_search": {
    "index": "kb_doc_search",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 19,
      "visible_unit_codes": 19
    }
  },
  "doc_meta": {
    "index": "kb_doc_meta",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 13,
      "visible_unit_codes": 13
    }
  },
  "qa": {
    "index": "kb_qa_*",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  }
}
```

## 六、结论

当前 ES 仍未满足关闭历史兼容开关的条件：

1. `kb_document_*` latest 数据仍有 399 条缺 `acl_tokens`。
2. `kb_doc_search` latest 数据仍有 19 条缺 `source_index/visible_unit_codes`。
3. `kb_doc_meta` latest 数据仍有 13 条缺 `source_index/visible_unit_codes`。
4. QA 索引当前审计结果已满足三个字段完整性要求。

上线建议：

1. 先执行历史权限投影补齐脚本。
2. 再执行本审计脚本确认全部缺字段为 0。
3. 最后灰度关闭：

```properties
kb.search.legacy-missing-permission-allow=false
kb.search.doc-search-missing-source-index-allow=false
```
