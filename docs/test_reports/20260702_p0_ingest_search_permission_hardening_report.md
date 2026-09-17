# 2026-07-02 P0 文档入库/检索/权限控制加固测试报告

## 一、实施范围

本轮只处理 P0 安全边界，未改动数据库结构，未执行 ES 数据迁移。

1. 检索结果缓存收敛：
   - 字面量召回结果不再无条件写入 `cacheable=true`。
   - 缓存服务不再信任上游显式 `cacheable=true`，会重新按 `visibility` 判定。
2. 入库缺失 ACL 默认拒绝：
   - `KB_INGEST_MISSING_ACL_POLICY` 默认值由 `internal` 改为 `no_access`。
   - `KB_DOC_META_DEFAULT_ACL_TOKENS` 默认值由 `_INTERNAL` 改为 `_NO_ACCESS`。
3. 入库异步执行边界：
   - `DocIngestService` 使用已有 `ingestExecutor`，不再直接 `new Thread`。
   - 无参 `ingest(req)` 会优先从 `UserContextHolder` 读取当前用户，能取到用户时进入既有权限校验。

## 二、关键变更文件

1. `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordResultAssembleStep.java`
2. `java_service/src/main/java/com/boyang/search/service/SearchCacheService.java`
3. `java_service/src/main/java/com/boyang/search/service/DocIngestService.java`
4. `ai_service/core/permissions/acl_payload.py`
5. `ai_service/core/rag_pipeline.py`
6. `ai_service/core/indexing/doc_indexer.py`
7. `java_service/src/test/java/com/boyang/search/pipeline/steps/DocumentPermissionProjectionAssembleTest.java`
8. `java_service/src/test/java/com/boyang/search/service/SearchCacheServiceTest.java`
9. `ai_service/tools_and_tests/test_acl_payload_policy.py`

## 三、测试命令与结果

### 1. Java 检索结果权限投影与缓存测试

命令：

```powershell
mvn "-Dtest=DocumentPermissionProjectionAssembleTest,SearchCacheServiceTest" test
```

执行目录：

```text
E:\project\AI\knowledge-base\java_service
```

结果：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖点：

1. Keyword 结果仍保留 `source_index/index_code/owner_unit_code/visible_unit_codes/permission_version`。
2. 字面量召回合并 DEPT 文档时，`cacheable=false`。
3. 缓存服务遇到 `cacheable=true + visibility=DEPT` 时，不写入 Redis。
4. 缓存 key 继续包含权限策略版本与 JWT 角色。

### 2. Python ACL 缺失策略测试

命令：

```powershell
python ai_service\tools_and_tests\test_acl_payload_policy.py
```

执行目录：

```text
E:\project\AI\knowledge-base
```

结果：

```text
PASS acl payload policy tests
```

覆盖点：

1. 缺失 ACL 默认返回 `_NO_ACCESS`。
2. 显式 `missing_policy=internal` 仍保留历史兼容能力。
3. 显式 `missing_policy=no_access` 返回 `_NO_ACCESS`。
4. 显式 `missing_policy=error` 会抛出异常。
5. 非法 `acl_tokens_json` 遵循 `no_access` 策略。

## 四、边缘案例与结论

1. 字面量召回如果命中 DEPT/PRIVATE/GRANT 文档，不再因为旧逻辑被误标为可缓存。
2. 即使未来其它召回路径误传 `cacheable=true`，缓存服务仍会基于 `visibility` 二次判定。
3. 入库任务如果缺失 ACL 投影，默认写入 `_NO_ACCESS`，不会被当成内部文档召回。
4. HTTP 鉴权上下文存在时，无参 `ingest(req)` 会自动带入当前用户；内部定时任务仍兼容无用户上下文。

## 五、剩余风险

1. `DocImportController` 当前调用无参 `ingest(req)`，是否一定有 `UserContextHolder` 取决于拦截器链路；本轮做了兼容收紧，但没有强制阻断无用户上下文请求。
2. 离线脚本和历史文档仍可能显式配置 `_INTERNAL`，需要按迁移计划逐步治理。
3. 本轮未执行真实 ES/HTTP 端到端回归，只执行了针对性单元测试。
