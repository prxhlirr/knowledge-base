# 知识库升级 v2.0 过渡版 - 主检索 KNN numCandidates 策略测试报告

## 任务目标

修复亿级数据风险中的第三项：主检索链路 KNN `numCandidates` 在大规模 HNSW 图中候选遍历数过低，导致召回率下降。

本轮聚焦 Java 主检索链路，包括：

1. Hybrid 模式主 chunk KNN。
2. Semantic 模式主 chunk KNN。
3. Hybrid SubQuery 多向量扩展 KNN。

Python QA KNN 中的 `num_candidates=50/100` 属于独立 QA 检索通道，本轮只记录风险，不合并修改。

## 本轮改动

1. `java_service/src/main/java/com/boyang/search/entity/SysAiTuningConfig.java`
   - 将 `knnNumCandidates` 的代码默认值从 `500` 提升到 `1000`。
   - 新增 `resolveKnnNumCandidates(int kVal)`：
     - 满足 ES 约束：`numCandidates >= k`。
     - 满足亿级过渡版下限：`numCandidates >= 1000`。
     - 保留数据库热配置能力：当 `knn_num_candidates` 配置为 `1500/2000` 时按配置生效。
     - 历史数据库中若仍是 `500`，主 chunk KNN 实际执行值会提升到 `1000`。

2. `java_service/src/main/java/com/boyang/search/pipeline/steps/HybridRecallStrategy.java`
   - 主 chunk KNN 改为调用 `config.resolveKnnNumCandidates(kVal)`。
   - SubQuery KNN 改为调用 `subQueryConfig.resolveKnnNumCandidates(20)`。

3. `java_service/src/main/java/com/boyang/search/pipeline/steps/SemanticRecallStrategy.java`
   - 主 chunk KNN 改为调用 `config.resolveKnnNumCandidates(kVal)`。

4. `java_service/src/test/java/com/boyang/search/entity/SysAiTuningConfigKnnCandidatesTest.java`
   - 覆盖默认值、历史小配置抬升、运维大配置保留、`k` 过大时满足 ES 约束。

## 测试命令

```bash
cd java_service
mvn -q -Dtest=SysAiTuningConfigKnnCandidatesTest test
```

补充扫描：

```bash
rg -n "numCandidates\\((100|200|500)|Math\\.max\\(config\\.getKnnNumCandidates|resolveKnnNumCandidates" java_service/src/main/java java_service/src/test/java
```

## 测试结果

```text
mvn -q -Dtest=SysAiTuningConfigKnnCandidatesTest test
通过
```

扫描结果显示 Java 主检索链路只剩统一策略函数调用：

```text
HybridRecallStrategy.java: 主 chunk KNN -> resolveKnnNumCandidates
HybridRecallStrategy.java: SubQuery KNN -> resolveKnnNumCandidates
SemanticRecallStrategy.java: 主 chunk KNN -> resolveKnnNumCandidates
```

## 生产结论

1. 原先 `numCandidates=500` 在亿级 HNSW 图中偏低的问题，在 Java 主检索链路已被抬升到过渡版最低 `1000`。
2. 运维仍可通过 `sys_ai_tuning_config.knn_num_candidates` 调高到 `1500/2000`，不需要重新发布代码。
3. 当召回窗口 `k` 增大时，策略自动保证 `numCandidates >= k*2`。
4. 本轮不会触发历史数据重建，也不改变 ES mapping。

## 剩余风险

1. Python QA KNN 仍存在独立候选数：
   - `ai_service/main.py` 中 QA KNN `num_candidates=50`。
   - `ai_service/core/rag_pipeline.py` 中长文 QA 生成/检索 `num_candidates=100`。
2. 候选数升高会增加 ES KNN 查询成本，生产建议配合 `search_audit_log` 延迟、ES slowlog、CPU、heap、segment memory 做压测。
3. 对严格权限过滤场景，单纯增大 `numCandidates` 只能缓解召回不足，不能完全替代“先按权限/索引缩小候选空间”的架构优化。
