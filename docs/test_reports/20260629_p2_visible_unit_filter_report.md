# P2 单位级 visible_unit_codes 过滤实现与测试报告

## 1. 测试目标

接入单位级权限过滤：

> 文档挂在 A 部门，则 A 部门及上级部门可见。

实现原则：

- 写入侧预先把可见单位集合写入 `visible_unit_codes`。
- 读取侧只用当前用户本单位码精确匹配 `visible_unit_codes`。
- 不使用用户祖先链匹配 `visible_unit_codes`，避免“下级用户看到上级文档”的反向越权。
- `global` 作为全局可见兜底值保留，兼容当前历史数据。

## 2. 涉及文件

- `java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`
- `java_service/src/test/java/com/boyang/search/pipeline/steps/EsRecallUtilsTest.java`

## 3. 代码验证结论

现有代码原本主要使用：

- `acl_tokens`
- `metadata.acl_tokens`
- `visibility`
- `owner_dept_id`
- `metadata.dept_code_full`

但没有真正把新字段 `visible_unit_codes` 纳入普通检索过滤。

本轮已接入：

1. `buildLegacyPermFilter(...)`
   - 覆盖普通 chunk 检索。
   - 过滤字段：
     - `visible_unit_codes`
     - `metadata.visible_unit_codes`

2. `buildDocSearchPermFilter(...)`
   - 覆盖 `kb_doc_search` 文档级预召回。
   - 过滤字段：
     - `visible_unit_codes`

3. `buildVisibleUnitValuesForCurrentUser()`
   - 返回：
     - `global`
     - 当前用户本单位码
   - 不展开用户祖先链。

## 4. 当前 ES 数据分布

当前真实数据统计：

| 索引 | owner_unit_code | visible_unit_codes |
| --- | --- | --- |
| `kb_document_public` | `global=22` | `global=22` |
| `kb_document_official` | `global=669` | `global=669` |
| `kb_doc_search` | 无聚合值 | 无聚合值 |

结论：

- 当前正式 chunk 数据全部是 `global`。
- 这意味着所有单位用户都会被单位过滤放行，无法形成单位正反样本。

## 5. 自动化测试

执行命令：

```powershell
mvn -q -Dtest=EsRecallUtilsTest test
mvn -q "-Dtest=EsRecallUtilsTest,IndexAclGuardTest,DocumentPermissionProjectionAssembleTest,SearchControllerQaPermissionProjectionTest" test
mvn -q -DskipTests compile
```

结果：

- `EsRecallUtilsTest` 通过
- 组合回归通过
- Java 编译通过

新增覆盖：

1. `visibleUnitValuesUseCurrentDeptWithoutAncestorExpansion`
   - 用户部门 `A-A01`
   - 过滤值只包含 `global` 和 `A-A01`
   - 不包含 `A`

2. `legacyPermissionFilterIncludesVisibleUnitFields`
   - 普通 chunk 过滤 DSL 包含：
     - `visible_unit_codes`
     - `metadata.visible_unit_codes`

3. `docSearchPermissionFilterIncludesTopLevelVisibleUnitFieldOnly`
   - `kb_doc_search` 过滤 DSL 包含：
     - `visible_unit_codes`
   - 不包含：
     - `metadata.visible_unit_codes`

## 6. 真实 HTTP 验证尝试

为构造单位正反样本，曾临时写入一条 `kb_document_law` 测试文档：

- `owner_unit_code=A01`
- `visible_unit_codes=["A01"]`
- 查询用户：
  - A01
  - B01

结果：

- A01 与 B01 HTTP 查询均返回 0。

根因判断：

- 临时 ES-only 文档无法完整进入业务后置链路。
- `/api/v1/search` 最终还会经过 registry / PermissionGuard / 召回策略 / 后置过滤。
- 临时裸写 ES 文档不能代表正式入库数据。

清理结果：

```json
{"count": 0}
```

临时文档已删除，没有留下测试数据。

## 7. 本轮结论

1. 单位级 `visible_unit_codes` 过滤已接入 Java 普通检索链路。
2. 过滤逻辑使用当前用户本单位码精确匹配，不使用用户祖先链，符合“A 及上级可见”的写入侧预计算模型。
3. `global` 保留为全局可见值，兼容当前历史数据。
4. 单元测试、组合回归、编译均通过。
5. 真实 HTTP 单位正反样本暂无法闭环，因为当前正式数据全部为 `global`，临时 ES-only 文档不能通过完整业务后置链路。

## 8. 下一步建议

下一步应通过正式入库链路构造一份非 `global` 单位样本：

1. 文档归属单位：`A01`
2. 写入字段：
   - `owner_unit_code=A01`
   - `visible_unit_codes=["A01", "A"]` 或按真实组织树生成 A01 及上级链
3. 使用两个 JWT 用户验证：
   - `deptCode=A01`：应可见
   - `deptCode=B01`：应不可见
4. 再跑 `/api/v1/search` 真实 E2E。

