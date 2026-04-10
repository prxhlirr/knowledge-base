# AI 离线服务环境动态部署实施指南 (Docker Compose)

在经历了源码的系统级解耦与镜像重建后，本版 `ai-service.tar` 彻底告别了硬编码，全线支持环境注入。
以下是给到现场实施/运维人员的标准化操作流程：

---

## 一、 准备物料与离线包上传
将从局域网编译好的下列物料上传至目标 Linux 服务器的部署目录（例如 `/opt/knowledge-base/`）：

1. **`ai-service.tar`**: 最新的 AI 微服务离线镜像（由 `build-image.bat` 导出）。
2. **`models/`**: [必须大目录] 含有 `onnx_native` 下 `bge-m3` 和 `bge-reranker-v2-m3` 等模型。
3. **`docker-compose.yml`**: [必备配置文件] 前面的构建记录中我们在 `ai_service` 下已生成过的演示模板。

*目录结构应当呈现如下形态：*
```text
/opt/knowledge-base/
  ├─ ai-service.tar
  ├─ docker-compose.yml
  └─ models/
      └─ onnx_native/
          ├─ bge-m3/
          └─ bge-reranker-v2-m3/
```

---

## 二、 加载 Docker 离线镜像层
在目标服务器上进入存放物料的目录，将打好的归档文件解包进本地 Docker Daemon：
```bash
cd /opt/knowledge-base/
docker load -i ai-service.tar
```
*预期输出:* `Loaded image: knowledge-base-ai:1.0.0`

---

## 三、 使用编辑器修改动态配置 (核心)
使用 `vim docker-compose.yml` 修改其中的环境变量。由于 AI 服务是底层引擎，需要对接数据库和中间件：

```yaml
version: '3.8'
services:
  knowledge-base-ai:
    image: knowledge-base-ai:1.0.0
    container_name: ai-service
    ports:
      - "8001:8001"
    volumes:
      # 【第一步：挂载物理模型】把相对路径的 models 投射给容器（左侧是宿主机，右侧是固定不用点的容器内挂载点）
      - ./models:/app/models
    environment:
      # 【第二步：对齐所有的后端基建 IP 及口令】
      - REDIS_HOST=192.168.x.x          # 必须填写现场实际拉起的 Redis 地址
      - REDIS_PORT=6379
      - REDIS_PASSWORD=liyz12345        # Redis 密码
      
      - ES_HOST=http://192.168.x.x:9200 # ES 主机地址
      
      - JAVA_SERVICE_HOST=http://192.168.x.x:8080 # Java 服务主调地址
      - JAVA_API_BASE=http://192.168.x.x:8080/api/v1
      
      # [固定不变] 容器内的挂载底层绝对路径匹配
      - MODEL_BASE_PATH=/app/models/onnx_native
      # [微服务内部签发]
      - KB_INTERNAL_TOKEN=kb-dev-token-change-me-in-prod
    restart: always
```

---

## 四、 后台拉起并校验服务
当你修改完毕（确保 Redis 和 Elasticsearch 在目标内网处于已运行状态），执行一键拉起：
```bash
docker-compose up -d
```
使用以下指令检查它是否顺畅装载了 15GB 的模型和 Redis 通信：
```bash
docker logs -f ai-service
```
*如果你看到 `[Lifespan] 主动预加载 AI 模型层...` 紧接着 `✅ Redis 成功建立通信连接！` 和 `Uvicorn running on http://0.0.0.0:8001`，代表系统级部署完美竣工！*
