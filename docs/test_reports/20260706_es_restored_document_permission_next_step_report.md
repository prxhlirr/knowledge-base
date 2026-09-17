# ES 恢复后 kb_document 权限链路执行报告

## 1. 本轮目标

ES 恢复后补齐上一轮未完成任务，并继续执行下一步：

1. 在线审计 `kb_document_*` mapping 是否满足权限和检索目标。
2. 执行历史权限字段补全 dry-run。
3. 基于真实 ES 数据判断是否可以进入写入补全。
4. 修复当前检索链路中已被真实 mapping 验证的问题。

## 2. ES 连通性

执行时间：2026-07-06 08:57:41 +08:00

结果：

- `http://localhost:9200` 返回 HTTP 200。
- ES 版本：8.6.2。
- 集群名：`docker-cluster`。

## 3. 在线 mapping 审计结果

开发口径输出：

- `docs/test_reports/20260706_kb_document_mapping_readiness_online_dev.json`

生产口径输出：

- `docs/test_reports/20260706_kb_document_mapping_readiness_online_prod.json`

开发口径摘要：

- 索引总数：6
- ready：0
- notReady：6
- errorCount：10
- warningCount：12

生产口径摘要：

- 索引总数：6
- ready：0
- notReady：6
- errorCount：16
- warningCount：6

核心问题：

| 索引 | 关键问题 |
| --- | --- |
| `kb_document_law` | `acl_tokens` 为 `text`；`metadata.source_index`、`metadata.visible_unit_codes` 为 `text` |
| `kb_document_news` | `acl_tokens` 为 `text`；`metadata.source_index`、`metadata.visible_unit_codes` 为 `text` |
| `kb_document_notice` | 缺少 `acl_tokens` mapping |
| `kb_document_official` | `acl_tokens` 为 `text` |
| `kb_document_public` | `acl_tokens` 为 `text` |
| `kb_document_v1` | 缺少 `acl_tokens` mapping |

生产口径额外问题：

- 所有 `kb_document_*` 当前 `number_of_replicas=0`，生产环境无高可用。
- 所有 `kb_document_*` 当前 `number_of_shards=1`，亿级前必须重新做容量规划。

## 4. 历史权限字段补全 dry-run

执行命令：

```bash
python -X utf8 ai_service/scripts/backfill_document_permission_projection.py \
  --es-host http://localhost:9200 \
  --indices "kb_document_*" \
  --batch-size 200 \
  --limit 1000 \
  --sample 20 \
  --output docs/test_reports/20260706_kb_document_permission_backfill_dry_run_online.json
```

输出结果：

- scanned：0
- dry_run_updates：0
- written：0
- failed：0

字段覆盖率复核：

| 索引 | 文档数 | 权限字段覆盖结论 |
| --- | ---: | --- |
| `kb_document_official` | 669 | `acl_tokens/source_index/index_code/owner_unit_code/visible_unit_codes/permission_version` 均已覆盖 |
| `kb_document_public` | 22 | 权限字段均已覆盖 |
| `kb_document_news` | 16 | 权限字段均已覆盖 |
| `kb_document_law` | 0 | 无当前数据 |
| `kb_document_notice` | 0 | 无当前数据 |
| `kb_document_v1` | 0 | 无当前数据 |

结论：

- 当前不是“历史数据缺字段”的问题。
- 当前主要是“已有字段 mapping 类型不适合权限精确过滤”的问题。
- 因此本轮没有执行 `--execute` 写入补全。

## 5. 真实 ES 抽样验证

抽样文档：

- index：`kb_document_news`
- token：`dept::620102`

命中结果：

| 查询字段 | 命中数 |
| --- | ---: |
| `acl_tokens` | 0 |
| `acl_tokens.keyword` | 16 |
| `metadata.acl_tokens` | 16 |
| `metadata.acl_tokens.keyword` | 0 |

结论：

- 当前根级 `acl_tokens` 为 `text` 时，直接使用 `terms acl_tokens` 会漏召回。
- `acl_tokens.keyword` 可以正确命中。
- 检索代码必须在过渡期同时查询 `acl_tokens` 和 `acl_tokens.keyword`。

## 6. 本轮代码修复

修改文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`
- `java_service/src/main/java/com/boyang/search/service/SimilarityService.java`
- `java_service/src/test/java/com/boyang/search/pipeline/steps/EsRecallUtilsTest.java`

修复内容：

1. `buildLegacyPermFilter` 增加：
   - `acl_tokens.keyword`
   - `metadata.acl_tokens.keyword`
2. `buildDocSearchPermFilter` 增加：
   - `acl_tokens.keyword`
3. `SimilarityService.appendAclFilter` 增加：
   - `acl_tokens.keyword`
   - `metadata.acl_tokens.keyword`
4. `EsRecallUtilsTest` 增加断言，保证 DSL 中包含 keyword 兼容分支。

## 7. 测试结果

Python 补全工具单测：

```bash
python -X utf8 ai_service/tools_and_tests/test_backfill_document_permission_projection.py
```

结果：

- 9 个测试全部通过。
- 有 `requests` 依赖版本 warning，不影响本轮逻辑。

Java 方法级单测：

```bash
mvn -q -Dtest=EsRecallUtilsTest#legacyPermissionFilterIncludesVisibleUnitFields+docSearchPermissionFilterIncludesTopLevelVisibleUnitFieldOnly test
```

结果：

- 通过。

Java 编译：

```bash
mvn -q -DskipTests compile
```

结果：

- 通过。

全量 `EsRecallUtilsTest` 当前未通过，失败原因不是本轮修改，而是 `JwtVerifier.UserIdentity` 中存在既有硬编码：

```java
this.deptCode = "620102900000";
```

该问题会导致单位权限判断使用错误部门编码，必须作为下一步 P0 修复。

## 8. 下一步任务

P0：修复 `JwtVerifier.UserIdentity` 硬编码 `deptCode`。

原因：

- 单位权限的第一性原理是“用户所属单位决定可见单位链”。
- 当前构造器忽略入参，强制写死为 `620102900000`。
- 这会导致所有非该部门用户的单位权限过滤都存在误召回或漏召回风险。

建议下一步执行：

1. 修复 `JwtVerifier.UserIdentity` 使用真实入参 `deptCode`。
2. 补充/恢复 `EsRecallUtilsTest` 中单位编码测试。
3. 运行 `EsRecallUtilsTest` 全量测试。
4. 再执行一次真实 ES 权限查询回归。

