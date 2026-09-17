# 知识库升级 v2.0 过渡版 - SearchController 文档分片明细 size 配置化测试报告

## 一、任务目标

从第一性原理看，`SearchController` 中的文档分片明细接口不是主召回链路，但属于前端查看文档内容的用户路径。原逻辑在按 `docId` 或 `fileName` 查询分片时固定 `size(500)`，在缺少关键参数的调试兜底路径固定 `size(10)`。这会影响大文档展示、ES 返回体大小和误用兜底路径时的压力控制。

本轮目标：

- 将文档分片明细正常查询 size 配置化。
- 将文档分片明细 fallback 查询 size 配置化。
- 保持历史默认值 `500/10` 不变。
- 配置非法时自动回落默认值。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/controller/SearchController.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.chunks.max-size` | `SEARCH_CHUNKS_MAX_SIZE` | `500` | 按 docId/fileName 查询文档分片明细的最大返回数 |
| `search.chunks.fallback-size` | `SEARCH_CHUNKS_FALLBACK_SIZE` | `10` | 缺少 docId/fileName 时调试兜底查询返回数 |

关键规则：

- 默认值保持历史行为。
- 配置为空、非数字、`<=0` 时自动回落默认值。
- 不改变 chunks 查询条件、排序、source 返回和后续组装逻辑。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/controller/SearchControllerTimeoutConfigTest.java`

新增覆盖：

- `resolveChunksSizeUsesDefaultValuesWhenBlank`
- `resolveChunksSizeUsesConfiguredPositiveValues`
- `resolveChunksSizeFallsBackForInvalidValues`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=SearchControllerTimeoutConfigTest -DforkCount=0 test
```

执行结果：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'size\(500\)|size\(10\)|SEARCH_CHUNKS_|chunksMaxSize|chunksFallbackSize|resolveChunks|resolvePositiveInt' java_service/src/main/java/com/boyang/search/controller/SearchController.java java_service/src/test/java/com/boyang/search/controller/SearchControllerTimeoutConfigTest.java
```

核对结论：

- 原固定 `size(500)` 已替换为 `resolveChunksMaxSize()`。
- 原固定 `size(10)` 已替换为 `resolveChunksFallbackSize()`。
- 新增解析方法和配置项已被测试覆盖。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置 chunks size | 使用历史默认 `500/10` | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为非数字、空、负数 | 回落默认值 | 已覆盖 |

## 五、生产结论

本轮修复后，文档分片明细接口的返回窗口已具备运行期配置能力。生产环境可根据平均文档长度、前端展示需求和 ES 响应体压力调节，不再需要为固定 `500/10` 重新发版。

后续建议：

- 继续处理 `SysSearchTagServiceImpl` 中后台打标同步的固定分片上限和 RestTemplate 超时。
- 最后统一汇总 v2.0 过渡版所有新增配置项，形成一份可交付配置说明。
