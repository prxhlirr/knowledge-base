# P2 Java QA 权限字段透传全流程测试报告

## 1. 本轮任务

继续执行 ES 权限优化实施计划中的 Java 侧 QA 结果透传验证与补齐。

本轮目标不是新增权限判定规则，而是保证 Python QA 检索已经返回的权限投影字段不会在 Java 结果组装阶段丢失：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`

## 2. 代码事实验证

已验证 Java QA 调用链路：

- `AiEngineGateway.fetchQaResults(...)`
  - 已向 Python QA KNN 请求传递 `readable_source_indexes`。
  - 返回 `List<Map<String, Object>> data`，网关层不裁剪 Python 返回字段。
- `AiEngineGateway.fetchQaResultsByBm25(...)`
  - 已向 Python QA BM25 请求传递 `readable_source_indexes`。
  - 返回原始 `data`，网关层不裁剪 Python 返回字段。
- `RerankStep.execute(...)`
  - FastTrack QA 路径会重新组装 `qaResult`。
  - 普通候选路径最终会执行 `docMap.remove("_source")`。
  - 因此权限字段若只存在于 `_source` 或 `_source.metadata`，最终响应会丢失。

结论：本轮缺陷不在 Python 过滤，也不在 Java 网关请求参数，而在 Java 最终响应装配层缺少权限字段顶层透传。

## 3. 修改范围

### 3.1 `java_service/src/main/java/com/boyang/search/pipeline/steps/RerankStep.java`

已补齐两条出站路径：

- FastTrack QA 路径：
  - 从 `ftSource` 顶层优先读取权限字段。
  - 缺失时从 `qaMeta` 兜底读取。
- 普通最终结果路径：
  - 在 `_source` 被移除前，将权限字段提升到最终 `docMap` 顶层。

新增私有方法：

- `putFirstPresent(...)`
  - 顶层值优先。
  - metadata 兜底。
  - 空字符串不透传。
  - 保持 `visible_unit_codes` 等列表值原样，不做字符串化。

### 3.2 `java_service/src/test/java/com/boyang/search/pipeline/steps/RerankStepPermissionProjectionTest.java`

新增单元测试覆盖权限投影最小契约：

- 顶层 `source_index` 优先于 metadata fallback。
- fallback 可用于 `visible_unit_codes`。
- 列表类型保持原对象，不被字符串化。
- 空字符串不写入最终响应。

## 4. 执行命令与结果

### 4.1 定向单元测试

```bash
mvn -q -Dtest=RerankStepPermissionProjectionTest test
```

结果：通过。

### 4.2 Java 编译验证

```bash
mvn -q -DskipTests compile
```

结果：通过。

## 5. 边界用例

已覆盖：

- `_source.source_index` 和 `metadata.source_index` 同时存在时，保留 `_source` 顶层值。
- `_source.visible_unit_codes` 缺失但 `metadata.visible_unit_codes` 存在时，可兜底透传。
- `owner_unit_code` 为空白字符串时，不写入最终响应。

建议后续在端到端测试中继续覆盖：

- Python QA 返回权限字段只在 `_source.metadata` 下的历史兼容场景。
- Python QA 返回权限字段只在 `_source` 顶层的新索引场景。
- Java `/search` 接口最终响应同时包含 QA 命中和普通文档命中时，二者权限字段均存在。

## 6. 剩余风险

本轮未启动完整 Java Web 服务做 HTTP 端到端验证，原因是本轮变更点位于纯 Java 结果装配层，已通过定向单测和编译验证。

下一步建议执行 Java `/search` 接口级用例，验证最终 HTTP 响应中 QA 命中项保留：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`

