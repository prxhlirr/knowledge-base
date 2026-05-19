# 知识库中台扩展需求补充 (MVP_PRD_EXTRA)

## 1. 业务演进背景
当前系统 (MVP) 已实现政务公文的向量化检索。随着业务发展，系统需进一步支撑**订单数据、支付流水**等非纯文本、高度结构化的异构数据接入。由于不同数据源的业务属性及安全等级差异巨大，需建立一套可水平扩展的数据物理隔离与逻辑统一鉴权架构。

## 2. 核心架构：多索引 + 统一权限元数据协议
为保证系统的高性能与可维护性，采用「按源分库、协议对齐」的设计思路。

### 2.1 物理存储策略
*   **按源分索引**：公文、订单、支付分别建立独立的 Elasticsearch 索引（如 `kb_document_v1`, `kb_order_v1`）。
*   **隔离理由**：
    *   **Schema 差异**：公文侧重语义切片，订单侧重精确字段值；
    *   **性能优化**：不同数据源可使用不同的分词器（IK vs Keyword）及分片权重；
    *   **演进独立**：某一业务类型的映射变更不影响其他业务运行。

### 2.2 统一权限元数据协议 (Unified Metadata Protocol)
所有接入系统的索引必须**强制包含**以下通用权限元数据字段，以确保 Java 网关能执行统一的权限过滤。

| 字段名 | 类型 | 说明 | 来源建议 |
| :--- | :--- | :--- | :--- |
| `data_source` | keyword | 数据源标识 (document/order/payment) | 系统入库入口自动注入 |
| `owner_dept_id` | integer | 归属部门 ID | 提取自上传者 Token |
| `owner_user_id` | keyword | 数据创建者/所有人 ID | 提取自上传者 Token |
| `security_level` | keyword | 密级 (public/internal/confidential/secret) | 上传时人工指定 |
| `visible_scope` | keyword | 可见范围策略 (all/dept_only/creator_only) | 业务逻辑自动映射 |
| `visible_depts` | integer[] | 可见部门白名单 | 行政下发规则自动计算 |
| `is_active` | boolean | 逻辑删除标记 (true/false) | 默认 true |

## 3. 核心机制流程

### 3.1 自动化打标机制 (Ingestion Phase)
在 Python AI 加工层或 Java 接入层，数据入库前需进行“自动加签”。**上传者仅需选择具体的「涉密等级」**，其余字段如 `dept_id`、`user_id` 均由网关层从当前会话身份中自动补全，避免人工误差。

### 3.2 动态权限注入 (Query Phase)
Java `SearchService` 在发起 ES 查询前，需执行「权限切面」：
1.  **身份解析**：从用户 Token 提取 `dept_id`, `roles`, `clearance`。
2.  **DSL 注入**：在 `bool.filter` 中加入对 `security_level` 和 `visible_depts` 的强过滤条件。
3.  **索引路由**：根据前端请求的 `data_source` 参数，决定将该过滤 DSL 路由至哪个物理索引。

## 4. 技术实施规范
*   **Index Template**：在 ES 中定义以 `kb_*` 为前缀的索引模板，强制对齐上述权限字段。
*   **别名机制**：对每一个业务分类保持版本化（如 `kb_doc_v1` -> `alias: kb_doc`），方便进行无感知迁移。

---
> 备案状态：已提交架构评审。下一步任务：将公文索引 `gov_doc_vector_v1` 逐步对齐该元数据规范。
