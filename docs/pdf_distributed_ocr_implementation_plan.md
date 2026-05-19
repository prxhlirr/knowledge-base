# PDF 长期演进细粒度执行计划技术白皮书 (Roadmap & Technical Spec)

## 1. 引言与目标定位 (Executive Summary)

为彻底根治 RAG 应用在处理超长 PDF 或低劣三明治扫描 PDF 时的**同步计算阻塞**、**显存溢出风险**以及**脏语义层污染**，本项目制定此细粒度重构执行计划。

本计划的核心设计思想为：**“动静算力分离”** 与 **“页级无状态分片投递”**。通过三个阶段的渐进式升级，在确保系统 100% 高可用度的同时，实现文档处理吞吐量的指数级攀升。

---

## 2. 🛠️ 阶段拆解与物理实施清单

### 🏁 第一阶段：算力单元剥离，OCR 模块微服务化 (容器级物理隔离)
*   **本阶段核心收益**：将 `import paddle` 等重型运行时从主 RAG Worker 中物理剥离，彻底消除由于单进程频繁内存分配导致的宿主进程雪崩（OOM）隐患。

| 任务 ID | 细分动作与技术细节 | 涉及物理文件/路径 | 交付标准 |
| :--- | :--- | :--- | :--- |
| **Task 1.1** | **构建 FastAPI 无状态 OCR 推理端点**<br>1. 新建子工程根目录，封装 PaddleOCR 物理加载逻辑；<br>2. 声明接口 `POST /api/v1/ocr/extract_page`；<br>3. 单例模式实例化 `PPStructure` 引擎。 | `ai_service/ocr_engine/`<br>`ai_service/ocr_engine/main_ocr.py` | 接口吞吐正常，单例成功热加载，并发请求不重合。 |
| **Task 1.2** | **构建独立的 Docker 运行底座**<br>1. 编写 `Dockerfile.ocr`（仅包含推理必要 runtime）；<br>2. 在 `docker-compose` 中追加算力子节点，共享宿主机 CUDA 依赖；<br>3. 挂载独立的日志与监控路径。 | `ai_service/Dockerfile.ocr`<br>`docker-compose.yml` | 容器成功启动并接入主内网网格，可通过健康探针。 |
| **Task 1.3** | **主 Worker OCR 通讯重构 (Thin Client 改造)**<br>1. 清理主服务冗余推理依赖，缩减其 60% 的依赖体积；<br>2. 使用 `httpx` 重构适配器，将物理图片渲染流打成字节推送到微服务。 | [`ocr_pipeline.py`](file:///e:/project/AI/knowledge-base/ai_service/core/parsing/pdf_transforms/ocr_pipeline.py) | 主 Worker 内存降至轻载水平，调用链路完美连通。 |

---

### ⚡ 第二阶段：分布式页级分片与 Redis 栅栏状态机 (消灭长尾阻塞)
*   **本阶段核心收益**：彻底废除传统的“单文件同步解析”模式。大型扫描合同将被秒级“解体”为单页任务，并行压入算力池，处理时间将由**“随着页数线性膨胀”**转化为**“极小常数时间”**。

| 任务 ID | 细分动作与技术细节 | 涉及物理文件/路径 | 交付标准 |
| :--- | :--- | :--- | :--- |
| **Task 2.1** | **实现零 IO 内存物理页切割器 (PageSplitter)**<br>基于 `fitz.insert_pdf` 在内存中抽取物理页结构。严禁落地磁盘，控制耗时 $< 10\text{ms}$。 | `ai_service/core/parsing/pdf_utils/splitter.py` | 极速分拆 300 页 PDF 不产生任何本地脏垃圾文件。 |
| **Task 2.2** | **分布式任务流打通 (Task Publisher/Consumer)**<br>引入 Celery 或 python-rq 并发框架。注册页级任务 `process_page_task(task_id, page_idx, bytes)` 并配置分发路由。 | `ai_service/task_worker_ocr.py`<br>`ai_service/config/celery_config.py` | Worker 集群能够抢占式地分流吞噬 Redis 页队列。 |
| **Task 2.3** | **设计并实施 Redis 原子栅栏 (Fence Gate)**<br>1. **元信息元组**：`HSET doc_meta:{tid} total N counter 0`；<br>2. **乱序暂存架**：`HSET doc_content:{tid} page_{idx} {text}`；<br>3. **栅栏开启判定**：原子操作 `counter`。如果 $\text{counter} == \text{total}$，触发广播投递。 | `ai_service/core/parsing/pdf_utils/fence.py` | 在强并发乱序回调下，精准捕捉“收齐”时刻且无死锁。 |
| **Task 2.4** | **编写 Reducer 物理重排器**<br>监听到集齐信号后，按照 $0 \dots (N-1)$ 从 Redis 读取正文并执行 Markdown 空行融合。随后异步清除 Redis 缓存。 | `ai_service/core/parsing/pdf_utils/reducer.py` | 拼接后的文本与物理纸质阅读顺序具有绝对空间重合度。 |

---

### 🛡️ 第三阶段：置信度加权质量门控与防噪 (对抗脏 PDF)
*   **本阶段核心收益**：为系统筑起一道智能化“反欺诈墙”。自动识别伪装成电子档的“低质乱码双层扫描件”，保障写入向量库的每一条语义数据都是高纯度的黄金数据。

| 任务 ID | 细分动作与技术细节 | 涉及物理文件/路径 | 交付标准 |
| :--- | :--- | :--- | :--- |
| **Task 3.1** | **开发多维 PageQualityEvaluator 评分引擎**<br>1. 计算 `U+FFFD` 等乱码和标点的高维占比密度；<br>2. 计算文本包围盒覆盖率。综合加权打分。 | `ai_service/core/parsing/quality_gate.py` | 系统具备对一页纸质量给出 `[0.0, 1.0]` 置信度的能力。 |
| **Task 3.2** | **实现页级动态多轨分流器 (Router Gateway)**<br>在任务切割之后，对每页独立运行 `Evaluator`。<br>评分 $< 0.6$ 的页面被贴标 `"FORCE_OCR"` 指令强制降级至微服务。 | `ai_service/core/parsing/pdf_processor.py` | 混合 PDF 中的“假电子页”被 100% 揪出并顺利重构。 |
| **Task 3.3** | **监控看齐与质量数据分析灰度 (Ops)**<br>在入库 Log 中记录 `page_eval_score` 变动。搭建简易看板观察生产环境下 OCR 被拉起的占比情况。 | 系统 Ingestion Pipeline | 可视化管理，具备阈值动态调优（Tuning）的工程底气。 |

---

## 3. 🧬 物理数据流与协议规约 (Protocols)

### 3.1 OCR 微服务请求协议 (HTTP API)
```json
POST /api/v1/ocr/extract_page
Content-Type: multipart/form-data

FormData:
- file: [Binary Bytes of Single Page PDF/Image]
- dpi: 200
- need_layout: true
```
**响应体：**
```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "page_index": 0,
    "raw_text": "## 标题文字内容...",
    "confidence": 0.97,
    "regions": [
      {
        "bbox": [50, 100, 545, 120],
        "type": "title",
        "text": "标题文字"
      }
    ]
  }
}
```

### 3.2 Redis 栅栏原子状态机逻辑 (Lua Script 模式实现参考)
```lua
-- 将单页 Markdown 存入哈希
redis.call("HSET", KEYS[1], ARGV[1], ARGV[2])
-- 递增计数器
local current_cnt = redis.call("HINCRBY", KEYS[2], "counter", 1)
local total_cnt = tonumber(redis.call("HGET", KEYS[2], "total"))

-- 如果已集齐，开启栅栏并广播
if current_cnt >= total_cnt then
    redis.call("PUBLISH", ARGV[3], ARGV[4])
    return 1
end
return 0
```

---

## 4. 📊 工程推进 ROI 评估与风险保障 (Risk Assessment)

| 推进序列 | 技术风险评级 | 建设工时 (人/天) | 风险对策 |
| :--- | :--- | :--- | :--- |
| **第一阶段 (解耦)** | 🟢 极低 (兼容层切换) | 2 天 | 主工程旧代码封存备份，通过环境变量 `OCR_MICROSERVICE_ENABLED=true` 开关随时回滚。 |
| **第二阶段 (分片)** | 🟡 中 (Redis 一致性) | 4 天 | 增加任务 TTL 过期保底。若 10 分钟栅栏未集齐，自动发起重试或发送钉钉预警。 |
| **第三阶段 (门控)** | 🟢 极低 (轻量判定) | 2 天 | 初始阶段采用灰度静默运行（Shadow Mode），只记录评分不拦截分流，观察一周无误后再物理开启。 |

---

## 5. 签署与存档声明

本《细粒度执行计划》是 **[`pdf_distributed_ocr_architecture.md`](file:///e:/project/AI/knowledge-base/docs/pdf_distributed_ocr_architecture.md)** 在工程层面的落地实施指南。

*   **编写人员**：Antigravity 全栈架构师
*   **生效日期**：2026年5月13日
*   **所属项目**：企业私域 RAG 知识库系统优化工程
