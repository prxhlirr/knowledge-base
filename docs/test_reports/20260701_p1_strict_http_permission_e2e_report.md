# P1 严格权限 HTTP 全链路复验报告

## 测试结论

通过。

本轮验证覆盖文档检索的真实 HTTP 链路：JWT 身份解析、角色索引 ACL、ES 前置过滤、关键词召回、结果组装、Redis 缓存 key 隔离、HTTP 响应权限投影字段。

## 本轮发现并修复的问题

1. 关键词召回的 `_source.includes` 裁剪掉了权限投影字段。
   - 根因：`KeywordRecallStrategy` 为降低 ES 返回体，只保留检索展示字段，未保留 `source_index/index_code/owner_unit_code/visible_unit_codes/permission_version`。
   - 影响：ES 已补齐权限字段且过滤生效，但 HTTP 响应无法带回权限字段，端到端审计失败。
   - 修复：新旧关键词召回 source filter 均显式保留权限投影字段。

2. 结果组装只读取 `_source/metadata`，没有兜底读取召回 doc/hit 顶层字段。
   - 根因：部分召回路径会把权限字段平铺在候选文档顶层，组装阶段没有使用该 fallback。
   - 影响：即使召回阶段已有字段，最终响应仍可能丢失。
   - 修复：`KeywordResultAssembleStep` 新增 4 参数权限投影拷贝逻辑，读取顺序为 `_source -> metadata -> doc/hit fallback`。

3. Redis 搜索缓存 key 未纳入 JWT roles。
   - 根因：缓存 key 使用 `acl_tokens` 摘要，但本地 JWT roles 不一定进入 `acl_tokens`；角色又直接影响可读索引范围。
   - 影响：同一用户角色变化后，可能复用旧角色的搜索结果缓存。
   - 修复：`SearchCacheService.buildKey` 将 JWT roles 纳入 ACL 摘要。

## 单元测试

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn '-Dtest=SearchCacheServiceTest,KeywordRecallStrategySourceFilterTest,DocumentPermissionProjectionAssembleTest,EsRecallUtilsTest,SearchControllerQaPermissionProjectionTest' -DforkCount=0 test
```

结果：

- Tests run: 23
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

## 真实 HTTP 严格模式验证

启动参数关键项：

- `--jwt.dev-mode=false`
- `--search.trust-gateway-headers=true`
- `--kb.security.god-mode=false`
- `--kb.search.legacy-missing-permission-allow=false`
- `--kb.search.doc-search-missing-source-index-allow=false`
- ES：`127.0.0.1:9200`
- Java HTTP：`127.0.0.1:18080`

探针脚本：

```powershell
python scratch\strict_http_e2e_probe.py
```

验证结果：

```json
[
  {
    "name": "official_reader_official_query",
    "http_status": 200,
    "code": 200,
    "total": 1,
    "returned": 1,
    "source_indexes": ["kb_document_official"],
    "missing_projection": 0,
    "first_file": "test_notice.docx"
  },
  {
    "name": "public_reader_public_query",
    "http_status": 200,
    "code": 200,
    "total": 1,
    "returned": 1,
    "source_indexes": ["kb_document_public"],
    "missing_projection": 0,
    "first_file": "test_gongshi.docx"
  },
  {
    "name": "no_reader_official_query",
    "http_status": 200,
    "code": 200,
    "total": 0,
    "returned": 0,
    "source_indexes": [],
    "missing_projection": 0,
    "first_file": null
  },
  {
    "name": "admin_official_query",
    "http_status": 200,
    "code": 200,
    "total": 2,
    "returned": 2,
    "source_indexes": ["kb_document_official"],
    "missing_projection": 0,
    "first_file": ""
  }
]
```

## 验证覆盖的边界场景

- 角色 `official_reader` 只能看到 `kb_document_official`。
- 角色 `public_reader` 只能看到 `kb_document_public`。
- 无业务阅读角色用户不能看到受保护索引文档。
- `SYS_ADMIN` 管理员绕过普通索引 ACL，可查看命中文档。
- 返回结果必须带 `source_index` 和 `visible_unit_codes`，否则视为全链路失败。
- 同一用户不同 JWT roles 的搜索缓存 key 必须不同。

## 残留风险

- `KeywordResultAssembleStep.java` 存在历史中文注释编码异常。本轮未做大范围编码治理，避免扩大无关变更。
- HTTP 验证依赖本地 Redis/ES/数据库中的既有 ACL 规则和测试文档；后续如果变更种子数据，需要同步更新探针断言。
