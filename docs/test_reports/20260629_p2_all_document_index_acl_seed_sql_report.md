# P2 全量 kb_document_* 索引 ACL 初始化脚本测试报告

## 1. 测试目标

为当前所有 `kb_document_*` 物理索引准备显式索引 ACL 初始化脚本，使后续开启：

```text
KB_INDEX_ACL_DEFAULT_DENY=true
```

时不会依赖“无规则索引默认开放”的兼容行为。

本轮只生成和验证手工 PGSQL 脚本，未真实修改数据库。

## 2. 涉及文件

- `java_service/src/main/resources/db/manual/20260629_p2_seed_all_document_index_acl_pgsql.sql`
- `ai_service/tools_and_tests/test_index_acl_seed_sql.py`

## 3. 初始化角色约定

脚本内置以下默认角色编码：

| 索引 | 角色 |
| --- | --- |
| `kb_document_official` | `official_reader` |
| `kb_document_public` | `public_reader` |
| `kb_document_law` | `law_reader` |
| `kb_document_notice` | `notice_reader` |
| `kb_document_v1` | `legacy_reader` |
| `kb_document_news` | `news_reader` |

说明：

- 这些角色编码不要求在知识库数据库中预先建表。
- 运行时只要 JWT/IAM 为用户注入同名 `roleCode`，`IndexAclSubjectService` 即可匹配。
- 如果实际 IAM 角色编码不同，应在 DBA 手工执行前修改 `subject_value`。

## 4. 脚本特性

脚本使用 PostgreSQL 语法：

- `BEGIN; ... COMMIT;`
- `WITH seed(...) AS (VALUES ...)`
- `INSERT INTO ... SELECT ... FROM seed`
- `WHERE NOT EXISTS (...)`

安全性：

- 幂等执行，不重复插入已有 active ACL。
- 不修改现有 ACL。
- 提供按 `created_by='manual_p2_seed_all_document_index_acl'` 的可选回滚语句。
- 放在 `db/manual`，不是 Flyway 自动迁移。

## 5. 当前数据库差异预览

验证当前库中目标 ACL 是否已存在：

| 索引 | 角色 | 当前是否存在 |
| --- | --- | --- |
| `kb_document_official` | `official_reader` | 是 |
| `kb_document_public` | `public_reader` | 是 |
| `kb_document_law` | `law_reader` | 否 |
| `kb_document_notice` | `notice_reader` | 否 |
| `kb_document_v1` | `legacy_reader` | 否 |
| `kb_document_news` | `news_reader` | 否 |

结论：

- 当前真实执行该脚本时，预计只会新增 `law/notice/v1/news` 四条 ACL。
- `official/public` 已存在，不会重复插入。

## 6. 自动化验证

### 6.1 SQL 内容测试

执行命令：

```powershell
python ai_service/tools_and_tests/test_index_acl_seed_sql.py
```

结果：

- `PASS test_seed_sql_contains_all_current_document_indices`
- `PASS test_seed_sql_is_idempotent_and_manual`
- `PASS test_seed_sql_uses_role_read_allow_rows_only`

### 6.2 PostgreSQL 语法 dry-run

执行方式：

- 连接本地 PostgreSQL。
- 读取脚本执行段。
- 执行后立即 `ROLLBACK`。

结果：

```text
PASS pgsql syntax dry-run rolled back
```

说明：语法验证通过，且未落库。

### 6.3 事务内效果模拟

在事务中执行脚本后查询目标 ACL，再回滚。

结果：

| 索引 | 角色 | 事务内 count |
| --- | --- | ---: |
| `kb_document_law` | `law_reader` | 1 |
| `kb_document_news` | `news_reader` | 1 |
| `kb_document_notice` | `notice_reader` | 1 |
| `kb_document_official` | `official_reader` | 1 |
| `kb_document_public` | `public_reader` | 1 |
| `kb_document_v1` | `legacy_reader` | 1 |

最终：

```text
PASS effect simulation rolled back
```

## 7. 真实执行建议

执行前：

1. 由业务确认 `law_reader/notice_reader/legacy_reader/news_reader` 是否与 IAM 角色编码一致。
2. 若不一致，先修改脚本中的 `subject_value`。
3. DBA 在 PostgreSQL 中手工执行脚本。

执行后：

```sql
SELECT index_name, subject_type, subject_value, scope, effect, is_active
FROM public.kb_index_acl_subjects
WHERE index_name IN (
    'kb_document_official',
    'kb_document_public',
    'kb_document_law',
    'kb_document_notice',
    'kb_document_v1',
    'kb_document_news'
)
  AND scope = 'READ'
  AND is_active = 1
ORDER BY index_name, subject_type, subject_value;
```

然后再开启：

```text
KB_INDEX_ACL_DEFAULT_DENY=true
```

## 8. 本轮结论

1. 已生成全量 `kb_document_*` 索引 ACL 手工初始化脚本。
2. 脚本使用 PostgreSQL 语法，幂等、可回滚、不会重复插入。
3. 已完成 Python 内容测试、PG 语法 dry-run、事务内效果模拟。
4. 本轮未真实修改数据库，符合手工执行数据库脚本的要求。
5. 下一步可继续做单位级 `visible_unit_codes` 过滤验证。

