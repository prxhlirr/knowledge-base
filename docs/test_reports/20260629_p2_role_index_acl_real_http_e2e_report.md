# P2 角色级索引 ACL 真实 HTTP 验证报告

## 1. 测试目标

验证角色级索引 ACL 在真实 `/api/v1/search` 中是否生效：

- `official_reader` 只能读取 `kb_document_official`
- `public_reader` 只能读取 `kb_document_public`
- 无匹配角色用户不能读取已配置角色 ACL 的索引

## 2. 当前数据库配置

### 2.1 租户策略

`sys_tenant_policy` 当前核心数据：

| app_code | allowed_indices | force_file_type | min_security_level |
| --- | --- | --- | ---: |
| `ADMIN_MASTER_KEY` | `kb_document` | null | 0 |
| `VEND_A_7788` | `kb_document` | `document` | 0 |
| `VEND_B_9900` | `kb_document` | null | 1 |
| `boyang-kb` | `knowledge_base` | `PDF` | 1 |

本轮使用 `VEND_A_7788`，其最大可读范围为 `kb_document` 读别名。

### 2.2 索引 ACL

`kb_index_acl_subjects` 当前核心数据：

| index_name | subject_type | subject_value | scope | effect | is_active |
| --- | --- | --- | --- | --- | ---: |
| `kb_document_official` | `ROLE` | `official_reader` | `READ` | `ALLOW` | 1 |
| `kb_document_public` | `ROLE` | `public_reader` | `READ` | `ALLOW` | 1 |

## 3. 测试身份构造

由于 dev-mode Header 不读取角色，角色需要通过 JWT claims 注入。

本轮真实 HTTP 启动参数：

```text
--jwt.dev-mode=false
--search.trust-gateway-headers=true
--kb.security.god-mode=false
```

重要说明：

- `application-dev.yml` 默认 `kb.security.god-mode=true`。
- 如果不显式关闭，拦截器会把所有请求改成 `god-admin`，导致角色 ACL 验证失真。
- 本轮第一次交叉测试就因为未关闭 `god-mode` 观察到全部索引放行，已定位并修正测试启动参数。

JWT claim 示例：

```json
{
  "userId": "u-official",
  "deptCode": "000000",
  "appCode": "VEND_A_7788",
  "roles": ["official_reader"]
}
```

## 4. 真实 HTTP 验证结果

测试查询：

- official 查询词：`重点民生实事工程`
- public 查询词：`乡镇领导班子候选人`

### 4.1 official_reader

| 场景 | total | 返回 source_index |
| --- | ---: | --- |
| `official_reader -> official query` | 1 | `kb_document_official` |
| `official_reader -> public query` | 5 | 全部为 `kb_document_official` |

结论：

- `official_reader` 没有返回 `kb_document_public`。
- 即使查询词来自 public 文档，最终结果也被限制在 official 可读索引范围内。

### 4.2 public_reader

| 场景 | total | 返回 source_index |
| --- | ---: | --- |
| `public_reader -> public query` | 1 | `kb_document_public` |
| `public_reader -> official query` | 1 | `kb_document_public` |

结论：

- `public_reader` 没有返回 `kb_document_official`。
- 即使查询词来自 official 文档，最终结果也被限制在 public 可读索引范围内。

### 4.3 no_reader

| 场景 | total | 返回 source_index |
| --- | ---: | --- |
| `no_reader -> official query` | 0 | 空 |

结论：

- 无匹配角色用户无法读取已配置角色 ACL 的 `kb_document_official/public`。

## 5. resolvedIndexPattern 日志验证

关闭 `god-mode` 后，服务日志显示：

```text
official_reader:
index=kb_document_official,kb_document_law,kb_document_notice,kb_document_v1,kb_document_news

public_reader:
index=kb_document_public,kb_document_law,kb_document_notice,kb_document_v1,kb_document_news

no_reader:
index=kb_document_law,kb_document_notice,kb_document_v1,kb_document_news
```

解释：

- `kb_document_official` 和 `kb_document_public` 已配置 ACL，因此会按角色过滤。
- `kb_document_law/notice/v1/news` 当前没有配置 ACL，按现有兼容逻辑默认放行。
- 这些索引当前无数据，因此本轮没有造成实际泄露。

## 6. 新增单元回归

修改文件：

- `java_service/src/test/java/com/boyang/search/service/IndexAclGuardTest.java`

新增覆盖：

1. `roleReaderOnlyKeepsAllowedPhysicalIndexWhenRulesExist`
2. `readerWithoutMatchingRoleGetsNoReadableIndexWhenAllIndicesHaveRules`

验证点：

- 当多个物理索引都有 ACL 规则时，只有匹配角色的索引会保留。
- 当用户没有匹配角色且所有候选索引都有 ACL 规则时，返回 `__no_readable_index__`。

## 7. 自动化测试

执行命令：

```powershell
mvn -q -Dtest=IndexAclGuardTest test
mvn -q "-Dtest=IndexAclGuardTest,IndexAclSubjectServiceTest,DocumentPermissionProjectionAssembleTest,SearchControllerQaPermissionProjectionTest" test
mvn -q -DskipTests compile
```

结果：

- `IndexAclGuardTest` 通过
- 组合回归通过
- Java 编译通过

## 8. 风险与建议

### 8.1 已验证通过

角色级索引 ACL 对已有数据索引生效：

- `official_reader` 不会返回 public 文档。
- `public_reader` 不会返回 official 文档。
- 无匹配角色不会返回 official/public 文档。

### 8.2 仍需治理

当前设计保留了“无 ACL 规则索引默认开放”的兼容逻辑。

这对历史系统友好，但对“角色动态配置哪些索引可见”的严格需求存在风险：

- 如果未来 `kb_document_law/notice/v1/news` 写入数据，但没有配置 ACL，则普通用户仍可能读取这些索引。

建议后续二选一：

1. 为所有 `kb_document_*` 物理索引补齐显式 ACL 规则。
2. 增加严格模式配置，例如 `kb.index-acl.default-deny=true`，让无规则索引默认拒绝。

## 9. 本轮结论

1. 真实 HTTP 角色级索引 ACL 已通过核心验证。
2. 本轮发现并排除 `application-dev.yml` 默认 `god-mode=true` 对测试的干扰。
3. 代码层补充了 `IndexAclGuard` 单元回归。
4. 下一步建议实现或配置“未配置 ACL 的索引默认拒绝”策略，避免未来新增索引数据后出现默认开放风险。

