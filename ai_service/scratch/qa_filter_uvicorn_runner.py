"""
QA 权限过滤 HTTP 验证专用启动器。

业务功能：
  用最小能力启动 FastAPI，专门验证 QA 查询接口是否应用 readable_source_indexes。

关键流程：
  1. 禁用 worker，避免消费 Redis 入库任务；
  2. 禁用启动回填，避免服务启动时修改索引；
  3. 禁用模型预加载，只验证 BM25 QA 查询路径；
  4. 启动 127.0.0.1:8001，供本地测试请求调用。
"""

import os
import sys
from pathlib import Path

os.environ["AI_START_WORKER"] = "false"
os.environ["AUTO_BACKFILL_ON_STARTUP"] = "false"
os.environ["AI_MODEL_PRELOAD"] = "false"
os.environ["AI_CAPABILITIES"] = "qa"
os.environ["ES_HOST"] = "http://localhost:9200"
os.environ.setdefault("AI_PORT", "8002")

SERVICE_DIR = Path(__file__).resolve().parents[1]
os.chdir(SERVICE_DIR)
sys.path.insert(0, str(SERVICE_DIR))

import uvicorn  # noqa: E402


if __name__ == "__main__":
    uvicorn.run("main:app", host="127.0.0.1", port=int(os.environ["AI_PORT"]), reload=False, workers=1)
