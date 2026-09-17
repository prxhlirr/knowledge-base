# 2026-06-26 P2 历史 QA source_index 回填 dry-run 灰度验证报告

## 任务范围

本批任务计划对历史 QA `source_index` 回填脚本执行真实 ES 限量 dry-run 验证。

被测脚本：

- `ai_service/scripts/backfill_qa_source_index.py`

目标：

- 不传 `--execute`，确保不写 ES。
- 使用 `--limit 100 --sample 20` 验证真实 ES 中历史 QA 是否可被扫描。
- 验证 QA 能否通过 `answer_chunk_id` 或 `source` 反查 chunk 真实 `_index`。
- 记录可执行命令和阻断原因。

## 执行参数确认

脚本默认参数：

- `ES_HOST=http://localhost:9200`
- `qa_index=kb_qa_read`
- `chunk_index=kb_document`
- `mode=dry-run`

配置文件中发现的候选 ES 地址：

- `ai_service/.env`：`http://localhost:9200`
- `ai_service/docker-compose.yml`：宿主机 ES 地址配置为 `http://192.168.74.1:9200`
- `ai_service/config/.env`：`http://es86:9200`

## 执行命令与结果

### 1. 默认地址 dry-run

```bash
python ai_service/scripts/backfill_qa_source_index.py --limit 100 --sample 20
```

结果：失败，未进入 ES 查询扫描阶段。

根因：

```text
ConnectionRefusedError: [WinError 10061] 由于目标计算机积极拒绝，无法连接。
```

判断：

- `localhost:9200` 当前无 ES 服务监听。
- 这不是脚本查询语法问题，而是连接地址不可达。

### 2. 容器配置地址 dry-run

```bash
$env:ES_HOST='http://192.168.74.1:9200'
python ai_service/scripts/backfill_qa_source_index.py --limit 100 --sample 20
```

结果：失败，未进入 ES 查询扫描阶段。

根因：

```text
ConnectionRefusedError: [WinError 10061] 由于目标计算机积极拒绝，无法连接。
```

判断：

- `192.168.74.1:9200` 当前对本执行环境不可达或无服务监听。

### 3. config/.env 地址 dry-run

```bash
$env:ES_HOST='http://es86:9200'
python ai_service/scripts/backfill_qa_source_index.py --limit 100 --sample 20
```

结果：失败，未进入 ES 查询扫描阶段。

根因：

```text
NameResolutionError: Failed to resolve 'es86'
```

判断：

- 当前宿主环境无法解析 `es86`。

### 4. 宿主端口检查

```bash
Test-NetConnection -ComputerName localhost -Port 9200
```

结果：

```text
TcpTestSucceeded : False
```

判断：

- 宿主机 `localhost:9200` 无可用 ES 服务。

### 5. Docker 状态检查

```bash
docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}"
```

结果：失败。

根因：

```text
docker client must be run with elevated privileges
open //./pipe/docker_engine: The system cannot find the file specified
```

判断：

- 当前执行环境无法访问 Docker daemon。
- 无法通过 Docker 容器名或端口映射继续确认 ES 状态。

## 结论

本批真实 ES dry-run 灰度验证未能完成。

阻断点是运行环境无法连接 ES：

- `localhost:9200` 连接拒绝；
- `192.168.74.1:9200` 连接拒绝；
- `es86:9200` 无法解析；
- Docker daemon 当前不可访问。

脚本未执行任何写入操作，因为所有命令均未传入 `--execute`，且连接阶段已失败。

## 风险判断

当前失败不代表脚本逻辑错误。

上一批已通过单元测试验证：

- `source_index` 取自 chunk hit `_index`；
- `dry-run` 不触发 bulk 写入；
- `execute` 写入 QA hit 自身物理索引；
- 查询条件覆盖 `source_index` 缺失和空字符串。

本批只说明：当前 shell 环境不具备真实 ES dry-run 条件。

## 恢复前置条件

继续灰度验证前，需要满足任一条件：

1. 在宿主机启动 ES，并确保 `localhost:9200` 可访问；
2. 提供当前可访问的 ES HTTP 地址，并通过 `ES_HOST` 注入；
3. 在可访问 Docker daemon 的终端中执行；
4. 若 ES 需要认证，同时设置 `ES_USER` 和 `ES_PASS`。

## 可复跑命令

### 连接检查

```bash
Test-NetConnection -ComputerName localhost -Port 9200
```

或：

```bash
Test-NetConnection -ComputerName <ES_HOST_IP> -Port 9200
```

### dry-run 抽样验证

```bash
$env:ES_HOST='http://<ES_HOST_IP>:9200'
python ai_service/scripts/backfill_qa_source_index.py --limit 100 --sample 20
```

如需认证：

```bash
$env:ES_HOST='http://<ES_HOST_IP>:9200'
$env:ES_USER='<user>'
$env:ES_PASS='<password>'
python ai_service/scripts/backfill_qa_source_index.py --limit 100 --sample 20
```

### 小批量真实写入

仅在 dry-run 样例确认正确后执行：

```bash
$env:ES_HOST='http://<ES_HOST_IP>:9200'
python ai_service/scripts/backfill_qa_source_index.py --execute --limit 100 --sample 20
```

### 全量回填

仅在小批量写入和 QA 权限检索验证通过后执行：

```bash
$env:ES_HOST='http://<ES_HOST_IP>:9200'
python ai_service/scripts/backfill_qa_source_index.py --execute --batch-size 500
```

## 后续建议

下一步不建议直接全量执行。

应先恢复 ES 连接条件，再按以下顺序继续：

1. dry-run `--limit 100`；
2. 人工抽查样例；
3. execute `--limit 100`；
4. 验证 QA 查询侧权限过滤；
5. 全量执行。
