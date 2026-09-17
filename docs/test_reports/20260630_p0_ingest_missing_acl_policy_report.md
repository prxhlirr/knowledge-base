# P0 入库缺失 ACL 策略测试报告

## 结论

已将 Python 入库侧 `acl_tokens` 缺失处理从固定降级 `_INTERNAL` 改为可配置策略。

新增环境变量：

```text
KB_INGEST_MISSING_ACL_POLICY=internal|no_access|error
```

默认：

```text
internal
```

保持历史兼容。

严格模式建议：

```text
KB_INGEST_MISSING_ACL_POLICY=no_access
```

或在验证环境使用：

```text
KB_INGEST_MISSING_ACL_POLICY=error
```

## 第一性原理校验

`acl_tokens` 是 ES 前置权限过滤的核心事实投影。

当该字段缺失时，系统并不知道文档应该对谁可见。固定降级为 `_INTERNAL` 会把未知权限解释为“所有登录用户可见”，存在扩大授权风险。

因此本轮改为：

- 兼容模式：`internal`
- 失败关闭：`no_access`
- 严格失败：`error`

## 代码变更

- `ai_service/core/permissions/acl_payload.py`
  - 新增 `resolve_acl_tokens_from_metadata`。
  - 支持 `acl_tokens_json`、旧 `acl_tokens` 字符串、缺失策略。
- `ai_service/core/rag_pipeline.py`
  - 使用 `resolve_acl_tokens_from_metadata`。
  - 通过 `KB_INGEST_MISSING_ACL_POLICY` 控制缺失/非法 ACL 行为。
- `ai_service/tools_and_tests/test_acl_payload_policy.py`
  - 覆盖合法 JSON、旧字符串、`internal/no_access/error` 策略、非法 JSON。

## 已执行测试

```powershell
python -m py_compile ai_service\core\permissions\acl_payload.py ai_service\core\rag_pipeline.py ai_service\tools_and_tests\test_acl_payload_policy.py
```

结果：通过。

```powershell
python ai_service\tools_and_tests\test_acl_payload_policy.py
```

结果：

```text
PASS acl payload policy tests
```

## 上线建议

1. 当前生产先保持默认 `internal`，不改变历史行为。
2. 观察恢复任务和正式入库 payload 是否均已稳定携带 `acl_tokens_json`。
3. 灰度切换为 `no_access`，避免未来未知权限文档被放宽。
4. CI 或测试环境可使用 `error`，尽早暴露入口缺字段问题。
