# P1 单位权限投影标准化测试报告

## 一、任务目标

本任务修复文档初始入库单位投影与元数据更新单位投影之间的编码形态不一致问题。

第一性原理判断：

- 单位权限的业务语义是“同一个组织节点”，不是某一种字符串外观。
- 如果初始入库写 `620102000000`，元数据更新写 `620102`，同一文档在不同生命周期会产生不同投影形态。
- 检索侧虽然可以兼容两种编码，但新数据不应继续制造格式分裂。

## 二、代码变更

文件：`java_service/src/main/java/com/boyang/search/service/DocIngestService.java`

变更点：

- `buildUnitPermissionProjection(String deptCode)` 中，`ownerUnitCode` 改为使用：

```java
DeptTreeService.normalizeDeptCode(deptCode)
```

影响字段：

```text
ownerUnitCode
visibleUnitCodes
permissionVersion
```

其中 `visibleUnitCodes` 继续来自 `deptTreeService.buildAclChain(ownerUnitCode)`，与 `KbDocRegistryService.updateMeta()` 后的单位投影语义保持一致。

## 三、测试用例

文件：`java_service/src/test/java/com/boyang/search/service/DocIngestServiceUnitPermissionProjectionTest.java`

调整用例：

1. `unitPermissionProjectionUsesDeptChainFromJavaPermissionDomain`
   - 输入：`620102000000`
   - 期望 owner：`620102`
   - 期望 visible chain：`["620102", "6201", "62"]`

回归用例：

2. `unitPermissionProjectionFallsBackToGlobalForBlankDept`
   - 空单位仍写入 `global`。

联动回归：

- `KbDocRegistryServicePermissionMetaUpdateTest`
  - 验证元数据更新触发权限投影时仍按标准化单位链同步。

## 四、执行命令

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn '-Dtest=DocIngestServiceUnitPermissionProjectionTest,KbDocRegistryServicePermissionMetaUpdateTest' -DforkCount=0 test
```

## 五、测试结果

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 六、上线影响

1. 新入库文档的 `owner_unit_code` 会写入标准化编码。
2. 已入库历史文档不会自动变化，需要通过历史权限投影补齐脚本处理。
3. 当前 ES 检索侧仍保留原始编码与标准化编码兼容，因此本次变更不会导致旧数据不可检索。
