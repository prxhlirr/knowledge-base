# 敏感内容策略生产级优化方案

## 目标

敏感内容策略必须覆盖检索结果、文档预览、问答上下文、模型输出和原文访问出口，并满足以下生产要求：

- 策略变更后结果一致，不继续命中旧缓存。
- 大词表匹配性能稳定。
- 正则策略可控，不能拖垮请求线程。
- 命中结果可审计、可解释，但审计日志不能二次泄密。
- AI 服务保持基础能力服务，不承载业务权限策略。

## 分层架构

1. 策略定义层：`kb_sensitive_policy` 保存策略配置。
2. 策略编译层：将 WORD/REGEX/PERSON/ORG 等策略编译为运行时 matcher。
3. 运行匹配层：SEARCH/PREVIEW/QA/DOWNLOAD 统一调用策略引擎。
4. 审计解释层：记录命中策略、阶段、动作、文档和 traceId，不记录完整敏感原文。

## 策略字段扩展建议

后续可在 `kb_sensitive_policy` 增加：

- `name`：策略名称。
- `description`：策略说明。
- `match_mode`：`CONTAINS` / `EXACT` / `WORD_BOUNDARY` / `FUZZY`。
- `case_sensitive`：是否大小写敏感。
- `normalize_mode`：大小写、空白、繁简等归一化策略。
- `risk_level`：`LOW` / `MEDIUM` / `HIGH` / `CRITICAL`。
- `effective_at` / `expires_at`：生效与失效时间。
- `updated_by`：最后修改人。

## 匹配引擎

- 小规模 WORD 策略可继续使用当前 literal contains。
- 大词表必须引入 AC 自动机，避免每个请求逐条 contains。当前 Java 服务已实现按策略版本缓存的 literal Trie/AC 匹配器，后续可继续替换为成熟库或扩展为分片词库加载。
- REGEX 策略必须做长度限制、编译缓存和超时保护。
- PERSON/ORG/ENTITY 可先作为字典策略处理，后续再接实体识别服务。
- 所有出口统一返回 `FilterResult`，包含 blocked、changed、命中策略 ID、命中字段、命中次数、动作类型。

## 缓存一致性

敏感策略新增、禁用或修改后必须调用 `PolicyVersionService.bumpGlobalVersion()`。

搜索缓存 key 必须包含策略版本：

```text
search:cache:{appCode}:v{policyVersion}:{hash}
```

这样策略变化后无需 SCAN 删除旧缓存，新请求自然切换到新缓存空间。

## 出口控制

- SEARCH：过滤 `chunk_text/snippet/title/file_name/organization` 等展示字段。
- PREVIEW：过滤 chunk 预览内容。
- QA：先过滤证据、引用和 prompt，再缓冲模型输出，最终过滤后返回。
- DOWNLOAD：当前只做元数据阻断；若要正文级脱敏，必须改为后端代理流或生成脱敏副本。

## 审计建议

新增 `kb_sensitive_policy_hit_log`：

- `policy_id`
- `user_id`
- `app_code`
- `stage`
- `source_name`
- `doc_id`
- `field_name`
- `action`
- `hit_count`
- `trace_id`
- `created_at`

默认不记录完整命中文本，避免审计系统二次泄密。
