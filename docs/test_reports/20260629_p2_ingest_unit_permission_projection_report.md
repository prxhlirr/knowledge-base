# P2 文档入库单位权限投影链路测试报告

## 背景

上一轮已经在检索侧增加 `visible_unit_codes` 过滤，但使用临时 ES 文档无法完成真实 HTTP 正向验证。继续排查正式入库链路后确认：Java 入队 payload 已有 `deptCode` 与 `acl_tokens_json`，但缺少 `ownerUnitCode / visibleUnitCodes / permissionVersion`；Python 普通文档 Worker 也没有把这些字段透传给 `RAGPipeline`。

## 本轮变更

1. `java_service/src/main/java/com/boyang/search/service/DocIngestService.java`
   - 新增 `buildUnitPermissionProjection(String deptCode)`。
   - 入队 Redis payload 时写入：
     - `ownerUnitCode`
     - `visibleUnitCodes`
     - `permissionVersion`
   - 有 `deptCode` 时复用 `DeptTreeService.buildAclChain(deptCode)` 写入“本级 + 上级”单位链。
   - 空 `deptCode` 时兼容为 `global`。

2. `ai_service/task_worker.py`
   - 新增 `build_permission_projection_from_payload(payload)`。
   - QA Worker 与普通文档 Worker 共用同一字段转换逻辑。
   - 普通文档 Worker 将 Java payload 转换为 `RAGPipeline` 识别的：
     - `owner_unit_code`
     - `visible_unit_codes`
     - `permission_version`

3. 新增测试
   - `java_service/src/test/java/com/boyang/search/service/DocIngestServiceUnitPermissionProjectionTest.java`
   - `ai_service/tools_and_tests/test_task_worker_permission_projection.py`

## 第一性原理结论

单位层级是权限域数据，应由 Java 权限侧统一计算。Python 入库侧只负责透传索引字段，不能再次推导组织树，否则会出现两套规则漂移。

写入侧把文档归属单位及其上级写入 `visibleUnitCodes`，查询侧只使用当前用户单位加 `global` 做 terms 命中，即可满足“文档挂在 A 部门，则 A 部门及上级部门可见该文档”，同时避免子部门反向看到父部门文档。

## 测试记录

### Java 单测

命令：

```bash
mvn -q -Dtest=DocIngestServiceUnitPermissionProjectionTest test
```

结果：通过。

覆盖点：

- `deptCode=620102000000` 时，`visibleUnitCodes` 使用 `DeptTreeService.buildAclChain` 返回的单位链。
- 空单位时，`ownerUnitCode=global` 且 `visibleUnitCodes=["global"]`。
- `permissionVersion` 大于 0。

### Python 单测

命令：

```bash
python ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

结果：通过，4 个用例全部 OK。

覆盖点：

- 优先使用 Java camelCase 字段。
- 支持逗号/中文逗号分隔的可见单位字符串。
- 历史 payload 缺少新字段时回退到 `deptCode`。
- 完全缺少单位字段时回退到 `global`。

### Java 权限链路回归

命令：

```bash
mvn -q "-Dtest=DocIngestServiceUnitPermissionProjectionTest,EsRecallUtilsTest,IndexAclGuardTest,DocumentPermissionProjectionAssembleTest,SearchControllerQaPermissionProjectionTest" test
```

结果：通过。

覆盖点：

- 单位过滤 query 构造未回退。
- 索引 ACL 默认拒绝配置未回退。
- 普通文档响应权限字段投影未回退。
- QA 响应权限字段投影未回退。

### Java 编译

命令：

```bash
mvn -q -DskipTests compile
```

结果：通过。

## 尚未完成的真实闭环验证

仍需使用正式上传/同步入口创建一个非 `global` 的测试文档，而不是直接写 ES 临时文档。原因是完整检索链路依赖 registry、is_latest、source、targetIndex、acl_tokens、单位投影等多个字段协同，绕过正式入库会导致真实 HTTP 正向命中不稳定。

下一步建议：

1. 使用 LOCAL 或测试专用同步入口入库一个 `deptCode=620102000000` 的小文档。
2. 确认 ES chunk 文档、`kb_doc_meta`、`kb_doc_search` 均写入单位权限字段。
3. 使用 `deptCode=620102000000` 与 `deptCode=620200000000` 两个 JWT 做 HTTP 正反向验证。
4. 验证 QA 派生数据也继承相同 `source_index / owner_unit_code / visible_unit_codes / permission_version`。
