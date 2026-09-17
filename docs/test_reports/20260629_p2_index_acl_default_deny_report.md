# P2 索引 ACL 无规则默认拒绝配置测试报告

## 1. 测试目标

收口“未配置 ACL 的 `kb_document_*` 索引默认开放”的风险，同时不破坏历史兼容行为。

本轮实现一个显式配置：

```yaml
kb:
  index-acl:
    default-deny: ${KB_INDEX_ACL_DEFAULT_DENY:false}
```

语义：

- `false`：兼容模式，无 ACL 规则索引仍按租户范围放行。
- `true`：严格模式，无 ACL 规则索引默认拒绝。
- 超级管理员仍可读取全部索引。

## 2. 涉及文件

- `java_service/src/main/java/com/boyang/search/service/IndexAclGuard.java`
- `java_service/src/test/java/com/boyang/search/service/IndexAclGuardTest.java`
- `java_service/src/main/resources/application.yml`

## 3. 实现说明

`IndexAclGuard.isReadable(...)` 的判定顺序调整为：

1. 非 `kb_document_*` 物理索引：拒绝。
2. 超级管理员：放行。
3. 索引 ACL 决策为 `DENY`：拒绝。
4. 索引 ACL 决策为 `ALLOW`：放行。
5. 索引无 ACL 规则：
   - `kb.index-acl.default-deny=false`：放行。
   - `kb.index-acl.default-deny=true`：拒绝。

## 4. 新增单元测试

新增覆盖：

- `strictDefaultDenyRejectsIndexWithoutRules`
- `compatibilityModeAllowsIndexWithoutRules`

连同上一轮已有测试，当前覆盖：

- 超管可绕过索引 ACL。
- 匹配角色只保留允许索引。
- 无匹配角色在有规则索引上不可读。
- 严格模式下无规则索引不可读。
- 兼容模式下无规则索引仍可读。

## 5. 自动化测试

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

## 6. 真实 HTTP 严格模式验证

启动关键参数：

```text
--jwt.dev-mode=false
--search.trust-gateway-headers=true
--kb.security.god-mode=false
--kb.index-acl.default-deny=true
```

验证身份：

- `official_reader`
- `no_reader`

验证结果：

| 场景 | total | 返回 source_index |
| --- | ---: | --- |
| `strict official_reader -> official query` | 1 | `kb_document_official` |
| `strict official_reader -> public query` | 5 | 全部为 `kb_document_official` |
| `strict no_reader -> official query` | 0 | 空 |

日志验证：

```text
deny index without ACL rules index=kb_document_law defaultDeny=true
deny index without ACL rules index=kb_document_notice defaultDeny=true
deny index without ACL rules index=kb_document_v1 defaultDeny=true
deny index without ACL rules index=kb_document_news defaultDeny=true
index=kb_document_official
```

结论：

- 严格模式下，无规则索引已被拒绝。
- `official_reader` 的 resolved scope 已收窄为 `kb_document_official`。
- `no_reader` 对已配置 ACL 和无规则索引都不可读。

## 7. 上线建议

### 7.1 短期

默认保持：

```text
KB_INDEX_ACL_DEFAULT_DENY=false
```

原因：

- 不改变历史租户行为。
- 避免未补 ACL 的历史索引突然不可读。

### 7.2 切换严格模式前

必须完成：

1. 为所有承载业务数据的 `kb_document_*` 物理索引配置显式 ACL。
2. 用普通角色、无角色、管理员三类身份做 `/api/v1/search` 回归。
3. 确认 `application-dev.yml` 或启动参数中 `kb.security.god-mode=false`，否则角色测试会被超管兜底污染。

### 7.3 生产推荐

完成 ACL 补齐后设置：

```text
KB_INDEX_ACL_DEFAULT_DENY=true
```

这样未来新增索引如果忘记配置 ACL，会默认不可读，避免“默认开放”造成数据泄露。

## 8. 本轮结论

1. 已新增索引 ACL 默认拒绝配置。
2. 默认值保持兼容，不影响现有功能。
3. 严格模式单测和真实 HTTP E2E 均通过。
4. 下一步可以补齐 `kb_document_law/notice/v1/news` 等空索引的显式 ACL 初始化脚本，或者继续验证单位级 `visible_unit_codes` 过滤。

