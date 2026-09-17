# P1 严格权限兼容默认值切换测试报告

## 任务目标

在历史 ES 权限投影字段补齐并审计归零后，将检索侧“缺字段兼容放行”从默认开启切换为默认关闭，避免异常历史数据绕过权限过滤。

## 第一性原理判断

权限字段缺失不是权限事实。  
回填前，为了避免历史文档整体不可检索，可以短期兼容缺字段；回填后，如果继续把“缺字段”解释为“允许访问”，本质上是在用数据质量问题制造隐式授权。

因此严格模式切换顺序必须是：

1. 补齐历史 ES 权限投影。
2. 审计确认 `acl_tokens/source_index/visible_unit_codes` 缺失数归零。
3. 将缺字段兼容分支默认关闭。
4. 保留环境变量回滚入口。

## 代码变更

- `java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`
  - `kb.search.legacy-missing-permission-allow` 默认值由 `true` 改为 `false`。
  - `kb.search.doc-search-missing-source-index-allow` 默认值由 `true` 改为 `false`。
- `java_service/src/main/resources/application.yml`
  - 显式增加：
    - `kb.search.legacy-missing-permission-allow: ${KB_SEARCH_LEGACY_MISSING_PERMISSION_ALLOW:false}`
    - `kb.search.doc-search-missing-source-index-allow: ${KB_SEARCH_DOC_SEARCH_MISSING_SOURCE_INDEX_ALLOW:false}`
  - 修正配置片段中 `kb:` 与顶层 `search:` 被注释挤压导致的 YAML 语义不清问题。
- `java_service/src/test/java/com/boyang/search/pipeline/steps/EsRecallUtilsTest.java`
  - 默认行为改为严格模式断言。
  - 新增显式打开兼容开关时的回滚行为断言。

## 回滚方式

无需改代码，可通过环境变量临时恢复兼容行为：

```properties
KB_SEARCH_LEGACY_MISSING_PERMISSION_ALLOW=true
KB_SEARCH_DOC_SEARCH_MISSING_SOURCE_INDEX_ALLOW=true
```

## 验证记录

### 1. YAML 配置解析

命令：

```powershell
python -c "import yaml, pathlib; p=pathlib.Path('java_service/src/main/resources/application.yml'); data=yaml.safe_load(p.read_text(encoding='utf-8')); print(data['kb']['index-acl']); print(data['kb']['search']); print(data['search']['use-v2'])"
```

结果：

```text
{'default-deny': '${KB_INDEX_ACL_DEFAULT_DENY:false}'}
{'legacy-missing-permission-allow': '${KB_SEARCH_LEGACY_MISSING_PERMISSION_ALLOW:false}', 'doc-search-missing-source-index-allow': '${KB_SEARCH_DOC_SEARCH_MISSING_SOURCE_INDEX_ALLOW:false}'}
True
```

### 2. 权限投影 readiness 审计

命令：

```powershell
python ai_service\scripts\audit_permission_projection_readiness.py --fail-on-risk
```

结果：

```json
{
  "chunk": {
    "index": "kb_document_*",
    "latestField": "metadata.is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  },
  "doc_search": {
    "index": "kb_doc_search",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  },
  "doc_meta": {
    "index": "kb_doc_meta",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
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

### 3. 单元测试

命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -Dtest=EsRecallUtilsTest -DforkCount=0 test
```

结果：

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 4. 检索权限相关回归

命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn '-Dtest=EsRecallUtilsTest,DocumentPermissionProjectionAssembleTest,RerankStepPermissionProjectionTest,SearchControllerQaPermissionProjectionTest' -DforkCount=0 test
```

结果：

```text
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 结论

- ES 历史权限投影缺口已归零。
- 检索侧默认严格模式已生效。
- 显式兼容开关仍可用于紧急回滚。
- 相关权限 DSL、结果组装、QA 权限投影出口测试均通过。
