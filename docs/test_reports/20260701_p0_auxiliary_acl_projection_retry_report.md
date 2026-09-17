# P0 辅助索引 ACL 投影失败补偿测试报告

## 一、任务目标

本任务修复 ACL/单位权限完整投影时，只有 chunk 主索引失败才创建补偿任务的问题。

第一性原理判断：

- `kb_document_*` 是主检索索引，但 `kb_doc_meta`、`kb_doc_search`、`kb_qa_pairs` 也是检索入口。
- 权限变更后，只要任一检索相关索引投影失败，就会造成“同一文档在不同检索路径权限表现不一致”。
- 因此完整 ACL token 同步和单位链同步必须以“所有相关索引均成功”为成功标准。

## 二、代码变更

文件：`java_service/src/main/java/com/boyang/search/service/DocAclProjectionService.java`

变更点：

1. `syncAclTokensProjectionInternal(...)`
   - 原逻辑：只在 chunk 索引失败时创建 `ACL_TOKENS_SYNC` 重试任务。
   - 新逻辑：chunk、`kb_doc_meta`、`kb_doc_search`、QA 任一失败，均创建 `ACL_TOKENS_SYNC` 重试任务。

2. `syncUnitProjectionInternal(...)`
   - 原逻辑：只在 chunk 索引失败时创建 `UNIT_SYNC` 重试任务。
   - 新逻辑：chunk、`kb_doc_meta`、`kb_doc_search`、QA 任一失败，均创建 `UNIT_SYNC` 重试任务。

3. 返回值语义
   - 原先返回 chunk 主索引是否成功。
   - 现在返回全部检索相关索引是否成功，更符合“权限投影完整性”的业务语义。

## 三、测试用例

文件：`java_service/src/test/java/com/boyang/search/service/DocAclProjectionServiceTest.java`

新增用例：

1. `syncAclTokensProjectionCreatesReplayableTaskWhenAuxiliaryProjectionFails`
   - 模拟 `kb_doc_meta` ACL token 投影失败。
   - 验证创建 `ACL_TOKENS_SYNC` 可重放任务。

2. `syncUnitProjectionCreatesReplayableTaskWhenAuxiliaryProjectionFails`
   - 模拟 `kb_doc_search` 单位链投影失败。
   - 验证创建 `UNIT_SYNC` 可重放任务。

保留既有用例：

- chunk 失败创建完整同步任务。
- retryTask 可重放 `ACL_TOKENS_SYNC`。
- retryTask 可重放 `UNIT_SYNC`。
- 正常路径会同步 chunk/meta/search/QA 四类索引。

## 四、执行命令

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -Dtest=DocAclProjectionServiceTest -DforkCount=0 test
```

## 五、测试结果

```text
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 六、风险与边界

1. 本次只收口 ACL token 和单位链完整投影，删除同步、版本切换同步仍需继续评估。
2. 失败重试任务仍依赖 `kb_acl_projection_task` 表和现有定时重试机制。
3. 辅助索引短时失败时，会多创建完整同步任务；这是可接受的，因为完整覆盖写入具备幂等性。
