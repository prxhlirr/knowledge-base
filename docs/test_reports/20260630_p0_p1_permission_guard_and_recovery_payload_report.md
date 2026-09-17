# P0/P1 权限语义与恢复任务 Payload 修复测试报告

## 本轮目标

按照“文档挂在 A 部门，则 A 部门及上级部门可见”的新权限语义，修复两个会影响全流程正确性的关键点：

1. `PermissionGuard` 后置数据库裁决必须与 ES `visible_unit_codes` 过滤方向一致。
2. `DocTaskRecoveryJob` 恢复重推 Redis payload 时必须补齐单位权限投影字段。

## 根因分析

### 1. PermissionGuard DEPT 方向相反

代码核查发现，`PermissionGuard` 的 DEPT 分支使用：

```java
deptTreeService.isSubDept(docDept, userDept)
```

这表达的是“用户部门是文档部门的下级或同级”。但新模型写入侧已经把文档可见单位预计算为“文档本级 + 上级”，例如：

```text
docDept=620102 -> visibleUnitCodes=620102,6201,62
```

因此后置裁决必须判断：

```text
用户部门 是否是 文档部门 的本级或上级
```

否则 ES 先放行上级部门用户，`PermissionGuard` 又会误拒，造成检索结果丢失。

### 2. 恢复任务缺少新权限投影字段

`DocTaskRecoveryJob.buildRecoveryPayload()` 原先只恢复：

- `targetIndex`
- `visibility`
- `deptCode`

没有恢复：

- `ownerUnitCode`
- `visibleUnitCodes`
- `permissionVersion`

如果 Redis 队列丢失后由恢复任务重推，新 Worker 会收到缺字段 payload，导致新写入文档的单位权限投影不完整。

## 实施内容

### 1. 修复 PermissionGuard

文件：

```text
java_service/src/main/java/com/boyang/search/security/PermissionGuard.java
```

修改点：

- 单条 DEPT 判断：

```java
deptTreeService.isAncestorOrSelf(userDept, docDept)
```

- 批量 DEPT 判断同步改为同一方向。

### 2. 新增 PermissionGuard 测试

文件：

```text
java_service/src/test/java/com/boyang/search/security/PermissionGuardDeptVisibilityTest.java
```

覆盖：

- 文档部门 `620102`，用户 `620102` 可见。
- 文档部门 `620102`，用户上级 `6201` 可见。
- 文档部门 `620102`，用户上级 `62` 可见。
- 文档部门 `620102`，兄弟部门 `620103` 不可见。
- 文档部门 `620102`，下级 `62010201` 不可见。
- `620102000000 / 620100000000` 尾零标准化后仍可正确判断。

### 3. 修复 DocTaskRecoveryJob

文件：

```text
java_service/src/main/java/com/boyang/search/job/DocTaskRecoveryJob.java
```

新增：

- `buildRecoveryUnitProjection(String deptCode)`
- `buildAdministrativeAncestorChain(String deptCode)`
- `normalizeDeptCode(String code)`

恢复 payload 现在补齐：

- `ownerUnitCode`
- `visibleUnitCodes`
- `permissionVersion`

说明：

恢复任务没有注入完整部门树服务上下文，因此采用行政区划两位层级兜底生成“本级 + 上级”。非行政编码至少保留自身，避免错误扩权。

### 4. 新增恢复任务测试

文件：

```text
java_service/src/test/java/com/boyang/search/job/DocTaskRecoveryJobPayloadTest.java
```

覆盖：

- `deptCode=620102000000` 时：
  - `ownerUnitCode=620102000000`
  - `visibleUnitCodes=[620102,6201,62]`
  - `permissionVersion>0`
- 空 `deptCode` 时：
  - `ownerUnitCode=global`
  - `visibleUnitCodes=[global]`

## 测试记录

首次并行执行两个 Maven 测试时，本机 Java native memory 不足：

```text
Native memory allocation failed
```

该问题由并行 Maven 进程抢占内存导致。已停止残留 Java 进程，并改为串行测试，同时设置：

```bash
MAVEN_OPTS='-Xms64m -Xmx512m -XX:ReservedCodeCacheSize=64m'
```

### 单项测试

```bash
mvn -q -Dtest=PermissionGuardDeptVisibilityTest test
```

结果：通过。

```bash
mvn -q -Dtest=DocTaskRecoveryJobPayloadTest test
```

结果：通过。

### 权限链路回归

```bash
mvn -q "-Dtest=PermissionGuardDeptVisibilityTest,DocTaskRecoveryJobPayloadTest,EsRecallUtilsTest,IndexAclGuardTest,DocIngestServiceUnitPermissionProjectionTest,DocumentPermissionProjectionAssembleTest" test
```

结果：通过。

### 编译验证

```bash
mvn -q -DskipTests compile
```

结果：通过。

## 当前结论

本轮已修复两个会影响权限正确性的关键缺陷：

1. 后置数据库裁决已经与 ES 单位权限投影语义一致。
2. Redis 恢复重推链路不再丢失单位权限投影字段。

## 后续任务

1. 继续补 `task_worker_qa.py`，复用普通 Worker 的 payload 兼容函数。
2. 补齐 Python Worker 依赖后执行完整 ES 落库闭环。
3. 验证 `kb_document_* / kb_doc_meta / kb_doc_search / kb_qa_pairs` 四类索引字段一致。
4. 对 `SimilarityService` 的独立 ES 查询补单位过滤或确认后置裁决性能可接受。
