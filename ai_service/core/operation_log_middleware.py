"""
操作日志中间件（FastAPI Middleware）

业务功能：
    拦截所有 FastAPI HTTP 请求，自动记录操作日志到 sys_operation_log 表。
    与 Java 服务共用同一张表，通过 service_name='ai_service' 和 trace_id 区分关联。

关键设计：
    1. 提取 X-Trace-Id Header（来自 Java 服务调用时透传），实现跨端链路追踪
    2. 根据路由前缀映射表自动推断操作模块和操作名称，无需在每个接口打标
    3. asyncio.create_task 异步写库，不阻塞请求响应链路
    4. 写库失败仅打印警告，不向外抛出异常（业务主链路不受影响）
    5. 健康检查接口（/api/ai/health）不记录日志，避免心跳轮询产生大量无效记录

依赖：
    - psycopg2：Python PostgreSQL 驱动（ai_service 环境已安装）
    - 环境变量：PG_HOST / PG_PORT / PG_DB / PG_USER / PG_PASSWORD（与 Java 侧共用同一 PG 实例）
"""

import os
import time
import json
import asyncio
import uuid
from datetime import datetime
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

# ─── 路由 → 模块/操作 映射表 ──────────────────────────────────────────────────
# 根据请求路径前缀自动推断日志的 module 和 operation 字段，
# 无需在每个接口函数上打装饰器，维护成本极低
ROUTE_MODULE_MAP = {
    "/api/ai/vector/query":            ("向量推理", "Dense向量化"),
    "/api/ai/vector/sparse":           ("向量推理", "Sparse向量化"),
    "/api/ai/vector/dual":             ("向量推理", "双模向量化"),
    "/api/ai/vector/long-doc":         ("向量推理", "长文档向量化"),
    "/api/ai/vector/hyde":             ("意图处理", "HyDE向量化"),
    "/api/ai/colbert/score":           ("向量推理", "ColBERT重排"),
    "/api/ai/rerank":                  ("向量推理", "BGE重排"),
    "/api/ai/rerank/sentences":        ("向量推理", "句子重排"),
    "/api/ai/llm/rerank":              ("向量推理", "LLM重排"),
    "/api/ai/intent/rewrite":          ("意图处理", "查询改写"),
    "/api/ai/intent/rewrite_and_hyde": ("意图处理", "改写+HyDE"),
    "/api/ai/nlp/normalize":           ("NLP处理", "查询标准化"),
    "/api/ai/config/reload":           ("系统配置", "重载模型配置"),
}

# 不记录日志的路径（健康检查/心跳接口，避免大量无效记录）
SKIP_PATHS = {"/api/ai/health", "/docs", "/openapi.json", "/redoc"}

# ─── PostgreSQL 连接参数（复用与 Java 侧相同的 PG 实例）────────────────────────
# 通过环境变量注入，与 ai_service 现有的 ES/Redis 配置风格一致
PG_HOST     = os.getenv("PG_HOST",     "localhost")
PG_PORT     = int(os.getenv("PG_PORT", "5432"))
PG_DB       = os.getenv("PG_DB",       "knowledge_base")
PG_USER     = os.getenv("PG_USER",     "postgres")
PG_PASSWORD = os.getenv("PG_PASSWORD", "")


def _infer_module_operation(path: str):
    """
    根据请求路径推断操作模块和操作名称。

    业务功能：将 FastAPI 路由路径映射为业务可读的模块/操作名称对，
             供 sys_operation_log 表的 module/operation 字段使用。
    流程说明：先做精确匹配，若找不到则截取路径第3段做模糊分类。

    :param path: FastAPI 请求路径（如 /api/ai/vector/query）
    :return: (module, operation) 元组，匹配失败则返回 ("AI服务", path)
    """
    # 精确匹配优先（最准确）
    if path in ROUTE_MODULE_MAP:
        return ROUTE_MODULE_MAP[path]

    # 前缀模糊匹配（兜底）
    for prefix, info in ROUTE_MODULE_MAP.items():
        if path.startswith(prefix):
            return info

    # 完全未匹配：用路径第3段作为模块名
    parts = path.strip("/").split("/")
    module = parts[2] if len(parts) >= 3 else "AI服务"
    return (f"AI服务/{module}", path)


def _generate_trace_id() -> str:
    """生成 16 位短链路 ID（与 Java 侧 JwtAuthInterceptor 生成格式一致）"""
    return uuid.uuid4().hex[:16]


async def _save_log_async(log_data: dict):
    """
    异步写入操作日志到 sys_operation_log 表。

    业务功能：在独立协程中执行 PG INSERT，不阻塞请求主流程。
    关键方法：使用 psycopg2 同步驱动（在 asyncio executor 中运行，避免阻塞事件循环）。
    流程说明：
        1. 从环境变量读取 PG 连接参数（与 Java 侧共用同一 PG 实例）
        2. 执行 INSERT INTO sys_operation_log
        3. 任何异常仅打印警告，不向上抛出

    :param log_data: 包含所有日志字段的字典
    """
    def _do_insert():
        try:
            import psycopg2
            conn = psycopg2.connect(
                host=PG_HOST, port=PG_PORT, dbname=PG_DB,
                user=PG_USER, password=PG_PASSWORD,
                connect_timeout=3
            )
            with conn:
                with conn.cursor() as cur:
                    cur.execute("""
                        INSERT INTO sys_operation_log
                            (trace_id, service_name, module, operation, method, request_uri,
                             request_params, response_data, status_code, success, error_msg,
                             cost_ms, user_id, created_at)
                        VALUES
                            (%(trace_id)s, %(service_name)s, %(module)s, %(operation)s,
                             %(method)s, %(request_uri)s, %(request_params)s, %(response_data)s,
                             %(status_code)s, %(success)s, %(error_msg)s, %(cost_ms)s,
                             %(user_id)s, %(created_at)s)
                    """, log_data)
            conn.close()
        except Exception as e:
            import traceback
            print(f"⚠️ [OperationLog] 日志写库失败 trace_id={log_data.get('trace_id')}:\n{traceback.format_exc()}")

    # 在线程池中执行同步 IO，不阻塞 asyncio 事件循环
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _do_insert)


def _truncate(text: str, max_bytes: int = 2048) -> str:
    """
    截断字符串至指定字节数，超出时末尾追加 [truncated] 标记。
    使用字节数而非字符数，更准确控制 PG 存储大小（中文 UTF-8 为 3 字节）。
    """
    if not text:
        return text
    encoded = text.encode("utf-8")
    if len(encoded) <= max_bytes:
        return text
    cut = max_bytes - len(b"[truncated]")
    return encoded[:cut].decode("utf-8", errors="ignore") + "[truncated]"


class OperationLogMiddleware(BaseHTTPMiddleware):
    """
    FastAPI 操作日志中间件。

    业务功能：
        拦截所有 HTTP 请求，自动采集操作上下文并异步写入 sys_operation_log 表，
        实现 AI 服务侧的操作审计能力，与 Java 服务侧通过 trace_id 串联。

    配置参数（通过环境变量注入）：
        LOG_OP_REQUEST_MAX_BYTES：请求体快照字节上限（默认 2048）
        LOG_OP_RESPONSE_MAX_BYTES：响应体快照字节上限（默认 4096）
    """

    def __init__(self, app,
                 request_max_bytes: int = None,
                 response_max_bytes: int = None):
        super().__init__(app)
        # 优先读取环境变量，其次用构造参数，最后用默认值
        self._req_max  = request_max_bytes  or int(os.getenv("LOG_OP_REQUEST_MAX_BYTES",  "2048"))
        self._res_max  = response_max_bytes or int(os.getenv("LOG_OP_RESPONSE_MAX_BYTES", "4096"))

    async def dispatch(self, request: Request, call_next) -> Response:
        path = request.url.path

        # 跳过健康检查和文档接口，避免产生大量心跳日志
        if path in SKIP_PATHS:
            return await call_next(request)

        # ── 1. 提取 trace_id（优先 Java 透传，降级自生成）──────────────────
        # Java 侧调用 AI 服务时，从请求 attribute 中取出 JwtAuthInterceptor 生成的 traceId，
        # 通过 X-Trace-Id Header 透传，实现跨端链路追踪
        trace_id = request.headers.get("X-Trace-Id") or _generate_trace_id()

        # ── 2. 推断操作模块和操作名称（基于路由映射表）──────────────────────
        module, operation = _infer_module_operation(path)

        # ── 3. 读取请求体快照（截断）────────────────────────────────────────
        # 注意：读取 body 后必须重新包装 request，否则下游路由无法再次读取
        request_body_snapshot = None
        try:
            body_bytes = await request.body()
            if body_bytes:
                # 尝试解析为 JSON 并重新序列化（便于日志阅读）
                try:
                    body_obj = json.loads(body_bytes)
                    # 隐藏向量字段（避免 1024 维向量撑爆日志列）
                    if isinstance(body_obj, dict):
                        for key in ("vector", "dense_vector", "sparse_vector"):
                            if key in body_obj:
                                body_obj[key] = f"<{key}:hidden>"
                    request_body_snapshot = _truncate(json.dumps(body_obj, ensure_ascii=False), self._req_max)
                except Exception:
                    request_body_snapshot = _truncate(body_bytes.decode("utf-8", errors="ignore"), self._req_max)
        except Exception:
            pass

        # ── 4. 记录开始时间 ──────────────────────────────────────────────────
        start_time = time.time()

        # ── 5. 执行业务路由 ──────────────────────────────────────────────────
        status_code = 500
        success = False
        error_msg = None
        response_snapshot = None

        try:
            response = await call_next(request)
            status_code = response.status_code
            success = status_code < 500
        except Exception as e:
            error_msg = f"{type(e).__name__}: {str(e)}"
            # 异常时构建 500 响应
            from starlette.responses import JSONResponse
            response = JSONResponse({"code": 500, "msg": str(e)}, status_code=500)

        # ── 6. 计算耗时 ──────────────────────────────────────────────────────
        cost_ms = int((time.time() - start_time) * 1000)

        # ── 7. 异步写日志（不等待，不阻塞响应返回）──────────────────────────
        log_data = {
            "trace_id":       trace_id,
            "service_name":   "ai_service",
            "module":         module,
            "operation":      operation,
            "method":         request.method,
            "request_uri":    path,
            "request_params": request_body_snapshot,
            "response_data":  None,   # AI 响应体通常含大向量，默认不记录
            "status_code":    status_code,
            "success":        success,
            "error_msg":      error_msg,
            "cost_ms":        cost_ms,
            "user_id":        "system",   # AI 服务为内部调用，无用户身份
            "created_at":     datetime.utcnow().isoformat(),
        }
        # 使用 asyncio.create_task 完全异步执行，不阻塞当前协程
        asyncio.create_task(_save_log_async(log_data))

        return response
