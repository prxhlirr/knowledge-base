#!/usr/bin/env python
"""
ES 基础设施独立初始化脚本

功能：在 CI/CD 流水线或首次部署时单独执行，永远不作为服务启动流程的一部分调用。
      非零退出码表示执行失败，CI/CD 可捕获并阻止主服务部署。

用法：
  # 首次部署（创建全部索引 + 注册 Template）
  ES_SETUP_MODE=init python scripts/es_init.py

  # 追加 Mapping 字段（新增字段上线前，人工确认后执行）
  ES_SETUP_MODE=migrate python scripts/es_init.py

  # 校验所有索引是否存在（CI 健康检查、发布前门禁）
  ES_SETUP_MODE=safe python scripts/es_init.py

环境变量：
  ES_HOST            ES 连接地址，默认 http://localhost:9200
  ES_USER            ES Basic Auth 用户名（如无鉴权则留空）
  ES_PASSWORD        ES Basic Auth 密码（如无鉴权则留空）
  ES_SETUP_MODE      执行模式：safe / init / migrate，默认 safe
  JAVA_SERVICE_HOST  Java 服务地址（safe 模式拉取路由表用），默认 http://localhost:8080
  KB_INTERNAL_TOKEN  Java 内部接口鉴权 Token
"""
import os
import sys

# 将 ai_service 根目录加入 Python 路径，使 core 模块可正常导入
_script_dir   = os.path.dirname(os.path.abspath(__file__))
_service_root = os.path.dirname(_script_dir)
sys.path.insert(0, _service_root)

# 优先加载 .env（开发环境用，容器环境通过 docker/k8s 注入环境变量）
try:
    from dotenv import load_dotenv
    _container_env = "/app/config/.env"
    _local_env     = os.path.join(_service_root, ".env")
    _env_path      = _container_env if os.path.exists(_container_env) else _local_env
    if os.path.exists(_env_path):
        load_dotenv(dotenv_path=_env_path)
        print(f"[ESInit] 已加载环境变量: {_env_path}")
except ImportError:
    pass


def main():
    """
    业务功能：ES 基础设施初始化主入口。
    关键流程：读取 ES 连接配置 → 建立连接 → 调用 ESSetup.setup(mode) → 打印结果 → 退出。
    退出码：0 = 成功，1 = 失败（CI/CD 可据此阻断主服务部署）。
    """
    from elasticsearch import Elasticsearch
    from core.indexing.es_setup import ESSetup

    es_host  = os.getenv("ES_HOST", "http://localhost:9200")
    es_user  = os.getenv("ES_USER", "").strip()
    es_pass  = os.getenv("ES_PASSWORD", "").strip()
    mode     = os.getenv("ES_SETUP_MODE", "safe")

    print(f"[ESInit] 目标 ES: {es_host}")
    print(f"[ESInit] 执行模式: {mode}")

    # 建立 ES 连接
    es = Elasticsearch(
        es_host,
        basic_auth=(es_user, es_pass) if es_user else None,
        verify_certs=False,
        # 连接/请求超时各 10s，init 模式可能涉及多次创建操作
        request_timeout=10,
    )

    # 检查 ES 连通性
    try:
        if not es.ping():
            print(f"[ESInit] ❌ 无法连接 ES（{es_host}），请检查服务是否就绪")
            sys.exit(1)
    except Exception as e:
        print(f"[ESInit] ❌ ES 连接异常: {e}")
        sys.exit(1)

    # 执行初始化
    try:
        ESSetup(es).setup(mode=mode)
        print(f"[ESInit] ✅ 执行完成（mode={mode}）")
        sys.exit(0)
    except Exception as e:
        print(f"[ESInit] ❌ 执行失败（mode={mode}）: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
