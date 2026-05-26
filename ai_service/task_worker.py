import os
import json
import time
import threading
import urllib.request
import redis
from dotenv import load_dotenv
import sys

# 把项目根目录加入模块检索路径，以便可以导入 scripts 下的文件
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.append(BASE_DIR)
from core.rag_pipeline import RAGPipeline
load_dotenv()

REDIS_HOST = os.getenv("REDIS_HOST", "redis")
REDIS_PORT = int(os.getenv("REDIS_PORT", 6379))
# [Bug-4 修复] 生产环境密码必须通过环境变量注入，不允许明文默认值
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")
JAVA_API_BASE = os.getenv("JAVA_API_BASE", "http://knowledge-base-java:8080/api/v1")
# [A-3/A-4 修复] 用于内部接口鉴权（与 Java 内部 Token 一致，生产环境必须设置环境变量）
INTERNAL_TOKEN = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")

QUEUE_HIGH = "DOC_TASK_QUEUE_HIGH"  # 小文件高优先队列（<2MB）
QUEUE_LOW  = "DOC_TASK_QUEUE"       # 大文件标准队列（>=2MB）
# [Bug-3 修复] 死信队列：任务失败超过 MAX_RETRY 次后转入此队列
QUEUE_DLQ  = "DOC_TASK_DLQ"
MAX_RETRY  = 3  # 最大自动重试次数


def _payload_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    return str(value).strip().lower() in ("1", "true", "yes", "y", "on")


def main():
    print("⏳ 初始化 Redis 客户端并连接...")

    def _make_redis():
        """
        业务功能：建立带 TCP Keepalive 的 Redis 连接。
        核心参数说明：
          socket_keepalive=True: 开启 TCP keepalive，防止导表空闲连接被服务端踢掉
          health_check_interval=25: redis-py 每 25s 自动发送 PING，保持连接活性
          socket_connect_timeout=5: 连接超时 5s，避免无限阴塞
          retry_on_timeout=True: 连接超时自动重试一次
        """
        kwargs = {
            "host": REDIS_HOST,
            "port": REDIS_PORT,
            "decode_responses": True,
            "socket_keepalive": True,         # 开启 TCP keepalive防空闲踢连
            "socket_connect_timeout": 5,      # 连接超时 5s
            "health_check_interval": 25,      # 客户端自动 PING，保持连接活性
            "retry_on_timeout": True,         # 连接超时自动重试
        }
        if REDIS_PASSWORD:
            kwargs["password"] = REDIS_PASSWORD
        return redis.Redis(**kwargs)

    redis_client = _make_redis()

    # 探活验证与异常捕获
    try:
        redis_client.ping()
        print(f"✅ Redis 成功建立通信连接！(Host: {REDIS_HOST}:{REDIS_PORT})")
    except redis.exceptions.AuthenticationError:
        print(f"❌ Redis 鉴权失败：请检查 REDIS_PASSWORD 配置是否正确")
        return
    except Exception as e:
        print(f"❌ Redis 连接不可用: {e}")
        return


    print("🧠 加载 RAG 大模型与 Elasticsearch 链路...")
    pipeline = None
    # [Watchdog 配合] RAGPipeline 初始化加重试，避免 ES 临时抖动直接崩溃
    # 若重试全部失败则抛出异常，由 main.py 的 Watchdog 守护线程负责10s后重新拉起
    for _init_attempt in range(3):
        try:
            pipeline = RAGPipeline()
            print("✅ RAGPipeline 初始化成功")
            break
        except Exception as _init_err:
            print(f"⚠️ RAGPipeline 初始化失败（第 {_init_attempt + 1} 次）: {_init_err}")
            if _init_attempt < 2:
                time.sleep(15)
    if pipeline is None:
        raise RuntimeError("RAGPipeline 初始化连续失败 3 次，worker 退出（Watchdog 将负责重启）")

    # ─────────────────────────────────────────────────────────────────
    # [T5-1] QA Worker 内嵌线程（零 docker-compose / 镜像改动方案）
    # 设计原理：
    #   - QA 生成是 IO 密集型（LLM API 调用），独立线程不阻塞主 Worker
    #   - 共享已初始化的 pipeline 实例，避免重复加载模型（~30s 启动成本）
    #   - daemon=True：主进程退出时自动回收，无孤儿进程风险
    #   - 独立 Redis 连接：brpop 各自阻塞，互不干扰
    # ─────────────────────────────────────────────────────────────────
    def _qa_worker_loop():
        """
        业务功能：消费 QUEUE_QA 队列，为文档片段生成问答对并写入 kb_qa_pairs 索引。
        关键层次：作为线程内部实现，共享 pipeline 实例，零额外基础设施负担。
        """
        _QUEUE_QA  = "QUEUE_QA"
        _QUEUE_DLQ = "QUEUE_QA_DLQ"
        qa_redis   = _make_redis()
        _payload   = {}
        print(f"📋 [QA Worker] 内嵌线程启动，监听 [{_QUEUE_QA}]...")
        while True:
            try:
                result = qa_redis.brpop([_QUEUE_QA], timeout=30)
                if not result:
                    continue
                _, raw   = result
                _payload    = json.loads(raw)
                task_id     = _payload.get("taskId", "unknown")
                source_name = _payload.get("sourceName", "")
                fine_chunks = _payload.get("finChunks", [])
                acl_tokens  = _payload.get("acl_tokens", ["_INTERNAL"])
                file_hash   = _payload.get("fileBaseHash", "")

                if not fine_chunks:
                    continue

                print(f"📋 [QA Worker] 处理 taskId={task_id} chunks={len(fine_chunks)}")
                pipeline._generate_and_index_qa_pairs(
                    fine_chunks=fine_chunks,
                    source_name=source_name,
                    file_base_hash=file_hash,
                    acl_tokens=acl_tokens
                )
                print(f"✅ [QA Worker] 完成 taskId={task_id}")

            except redis.exceptions.ConnectionError as _ce:
                print(f"⚠️ [QA Worker] Redis 断连，3秒后重连: {_ce}")
                time.sleep(3)
                try:
                    qa_redis = _make_redis()
                except Exception:
                    time.sleep(10)
            except Exception as _qe:
                print(f"⚠️ [QA Worker] 任务失败 taskId={_payload.get('taskId', '?')}: {_qe}")
                _retry = _payload.get("retryCount", 0)
                try:
                    if _retry < MAX_RETRY:
                        _payload["retryCount"] = _retry + 1
                        qa_redis.lpush(_QUEUE_QA, json.dumps(_payload, ensure_ascii=False))
                    else:
                        qa_redis.lpush(_QUEUE_DLQ, json.dumps(_payload, ensure_ascii=False))
                        print(f"💀 [QA Worker] 达到最大重试次数，转入 {_QUEUE_DLQ}")
                except Exception:
                    pass

    threading.Thread(target=_qa_worker_loop, daemon=True, name="qa-worker").start()

    # 同时监听两个队列：HIGH 在前表示优先级更高（brpop 按参数顺序依次检查）
    print(f"🚀 开始阻塞监听队列: {QUEUE_HIGH} (高优先) → {QUEUE_LOW} (标准)")
    while True:

        try:
            # [BRPOP 超时修复] timeout=30 而非 0（永久阻塞）
            # 根因：timeout=0 会让连接处于长期空闲状态。Redis 服务端的 'timeout' 配置
            #   会主动断开超过阈值的空闲连接，导致 brpop 抛出 ConnectionError。
            # 修复：每 30s 自然返回 None，循环重新发起 brpop，
            #   连接得到新鲜使用，天然避免 空闲踢连闰题。
            result = redis_client.brpop([QUEUE_HIGH, QUEUE_LOW], timeout=30)
            if not result:
                continue
                
            _, payload_str = result
            payload = json.loads(payload_str)
            task_id = payload.get("taskId", "unknown_task")
            original_name = payload.get("originalName", "")
            file_path = payload.get("filePath", "")
            file_code = payload.get("fileCode", task_id)
            storage_mode = payload.get("storageMode", "MINIO") 
            storage_path = payload.get("storagePath", "")

            _task_start_time = time.time()
            
            # [Feature: 智能分流] 如果 Redis payload 中自带了 htmlContent (极速模式)
            html_content = payload.get("htmlContent", None)
            temp_html_file = None
            if html_content:
                import tempfile
                temp_html_file = tempfile.NamedTemporaryFile(suffix=".html", delete=False)
                temp_html_file.write(html_content.encode('utf-8', errors='ignore'))
                temp_html_file.close()
                file_path = temp_html_file.name
                original_name = original_name if original_name else f"db_export_{task_id}.html"

            source_name = original_name if original_name else os.path.basename(file_path)
            
            if storage_mode == "LOCAL_FS":
                print(f"📂 [DirectRead] 离线环境直读模式 -> {file_path}")
            else:
                print(f"📥 [Download] 远程下载模式 -> {source_name}")
            
            try:
                # [Bug-1 根治] 移除 delete_by_query 幂等清洗：
                # 原逻辑在写入前删除所有历史切片，若后续 process_and_index 中途失败，
                # 旧数据已删、新数据未写，文档在 ES 中彻底消失且用户无感知。
                # 根治方案：完全依赖 rag_pipeline 内部的 write-then-expire 机制：
                #   新版本 bulk 全部写入成功 → update_by_query 标记旧版本 is_latest=False
                # 任意时刻 ES 中至少有一个 is_latest=True 的版本，文档永不消失。

                if file_path and not file_path.startswith(("http://", "https://")) and not os.path.exists(file_path):
                    raise FileNotFoundError(
                        "Worker cannot access filePath. "
                        f"filePath={file_path}, storagePath={storage_path or '<empty>'}. "
                        "Use a presigned URL/MinIO path or mount the same LOCAL_FS path into the AI container."
                    )

                # [Date Fix] 兜底处理前端/Java 侧空时间导致 ES 报错
                pub_time_raw = payload.get("publishTime")
                clean_pub_time = pub_time_raw if str(pub_time_raw or "").strip() else None

                # 调起解析、分块和写入 ES 引擎的长流水线
                ext_meta = {
                    # [ES 内部用] snake_case 字段供 rag_pipeline 构建 ES metadata 时使用
                    "tag": payload.get("tag"),
                    "publish_time": clean_pub_time,
                    "document_number": payload.get("docNumber"),
                    "owner": payload.get("owner"),
                    "search_queries": payload.get("searchQueries"),
                    "visibility": payload.get("visibility", "INTERNAL"),
                    "dept_code":  payload.get("deptCode", ""),
                    "acl_tokens_json": payload.get("acl_tokens_json", ""),
                    "uploader_id": payload.get("uploaderId", ""),
                    "content_hash": payload.get("contentHash"),
                    "task_id": task_id,
                    # [Fix] bg_notify_java() 用 camelCase 读 ext_metadata，以下 key 保持与 Java payload 一致
                    # 根因：上面的 "publish_time"/"document_number" key 与 rag_pipeline 内部读取 key 不匹配，
                    #       导致 registry 回调中 docNumber/publishTime/unit 三个字段永远传 None → DB 存 NULL
                    "docNumber":   payload.get("docNumber"),
                    "publishTime": clean_pub_time,
                    "unit":        payload.get("unit"),
                    # [PDF扫描件] 从 Redis payload 读取 skip_pages，传给 RAGPipeline → ParserFactory → PdfProcessor
                    # Java 端 DocIngestService 写入：skipFirstPage=true → skip_pages=1
                    "skip_pages":  int(payload.get("skip_pages", 0)),
                    # 可选强制 OCR 开关：用于混合文本层/图片层 PDF 或现场已知扫描件。
                    "scanned": _payload_bool(payload.get("scanned", False)),
                    "force_ocr": _payload_bool(payload.get("force_ocr", payload.get("forceOcr", False))),
                }

                stats = pipeline.process_and_index(file_path, original_name=original_name, ext_metadata=ext_meta)
                
                # P0-2: Quality Gate 失败，抛出异常进入异常处理，保证 Java 接收 ERROR 状态且触发死信重试
                if (stats or {}).get("status") == "error":
                    raise RuntimeError((stats or {}).get("message", "解析失败，质量门控拦截"))

                # [Fix] 去重跳过/解析失败时也必须关闭 outbox WAITING，防?OutboxPoller 无限 retry?
                # 根因：process_and_index 返回 skipped/error 时，bg_notify_java() 不会被调用，
                #       outbox 永远停留 WAITING 状态，无法?OutboxPoller 处理?
                # 修复：带 skipped=True 标志调用 /doc/registry，Java 收到后将 outbox ?DONE?
                _result_status = (stats or {}).get("status", "ok")
                if _result_status in ("skipped", "error"):
                    try:
                        import urllib.request as _urq, json as _jmod
                        _reg_url = f"{JAVA_API_BASE}/internal/doc/registry"
                        _reg_body = _jmod.dumps({
                            "taskId":      task_id,
                            "sourceName":  original_name or os.path.basename(file_path),
                            "docVersion":  0,
                            "fileBaseHash": "",
                            "chunkCount":  0,
                            "skipped":     True,
                        }).encode("utf-8")
                        _reg_req = _urq.Request(_reg_url, data=_reg_body, headers={
                            "Content-Type": "application/json",
                            "X-Internal-Token": INTERNAL_TOKEN
                        })
                        with _urq.urlopen(_reg_req, timeout=5) as _r:
                            pass
                        print(f"\u2139\ufe0f [{task_id}] \u6587\u6863 {_result_status}\uff0c\u5df2\u901a\u77e5 Java \u5173\u95ed outbox")
                    except Exception as _skip_err:
                        print(f"\u26a0\ufe0f [{task_id}] \u901a\u77e5 Java \u5173\u95ed outbox \u5931\u8d25\uff08\u53ef\u63a5\u53d7\uff09: {_skip_err}")

                _parse_status = (stats or {}).get("parseStatus", "INDEXED")
                _task_status = "INDEXED_PARTIAL" if _parse_status == "INDEXED_PARTIAL" else "INDEXED"
                post_callback(task_id, _task_status, "")
                post_log_report(file_code, 1, "", stats)
                # [操作日志] 任务成功，写入操作日志表
                _task_cost_ms = int((time.time() - _task_start_time) * 1000)
                post_operation_log(task_id, file_code, True, _task_cost_ms)
                print(f"\u2705 \u4efb\u52a1\u5b8c\u6210\u6d41\u8f6c -> TaskID: {task_id}")
            except Exception as e:
                import traceback
                error_msg = traceback.format_exc()
                print(f"?任务执行中断 -> TaskID: {task_id}, Error: {str(e)}")
                
                # [Bug-3 修复] 失败任务不直接丢弃，支持死信队列重试机制
                # 最多重试 MAX_RETRY 次，超过后才真正废弃并通知 Java 记录 ERROR
                _retry_count = payload.get("retryCount", 0)
                if _retry_count < MAX_RETRY:
                    payload["retryCount"] = _retry_count + 1
                    payload["lastError"] = str(e)[:500]  # 截断过长错误信息
                    redis_client.lpush(QUEUE_LOW, json.dumps(payload, ensure_ascii=False))
                    print(f"♻️ [{task_id}] 处理失败（第 {_retry_count + 1} 次），已重新入低优先队列等待重试")
                else:
                    # 达到最大重试次数，转入死信队列并通知 Java 标记 ERROR
                    redis_client.lpush(QUEUE_DLQ, json.dumps(payload, ensure_ascii=False))
                    post_callback(task_id, "ERROR", str(e))
                    post_log_report(file_code, 2, error_msg)
                    # [操作日志] 达到最大重试次数，写入失败操作日志
                    _task_cost_ms = int((time.time() - _task_start_time) * 1000)
                    post_operation_log(task_id, file_code, False, _task_cost_ms, str(e)[:500])
                    print(f"💀 [{task_id}] 达到最大重试次数({MAX_RETRY})，已转入死信队列 {QUEUE_DLQ}")
            finally:
                # 无论成功失败，保证物理清理生成的临时文件，防爆盘
                if temp_html_file and os.path.exists(temp_html_file.name):
                    try:
                        os.remove(temp_html_file.name)
                    except Exception as clean_err:
                        print(f"⚠️ 清理临时文件异常 {temp_html_file.name}: {clean_err}")

                
        except redis.exceptions.ConnectionError as conn_err:
            # [REDIS 断连自动重连] 不再拓印后简单 sleep，而是重建 Redis 连接对象。
            # 根因：旧代码在 ConnectionError 后继续拿破连接发 brpop，导致每 5s 就报错一次的循环。
            print(f"⚠️ Redis 连接断开，3秒后重连: {conn_err}")
            time.sleep(3)
            try:
                redis_client = _make_redis()  # 重建连接对象
                redis_client.ping()
                print("✅ Redis 重连成功")
            except Exception as re_err:
                print(f"❌ Redis 重连失败，10秒后再试: {re_err}")
                time.sleep(10)
        except Exception as system_err:
            print(f"⚠️ Worker 系统级故障: {system_err}")
            time.sleep(5)

def post_callback(task_id: str, status: str, error_msg: str):
    """
    通过 HTTP 将每一个文章切片的解析最终下场告知 Java 持久化。
    [A-3 修复] 携带 X-Internal-Token Header，防止 Java 端 P2-3 鉴权返回 401 导致状态永远 PENDING。
    同时检查响应体状态码，非 200 时抛出异常以触发外层 ERROR 处理。
    """
    try:
        url = f"{JAVA_API_BASE}/internal/task/callback"
        data = json.dumps({
            "taskId": task_id,
            "status": status,
            "errorMsg": error_msg
        }).encode('utf-8')
        # [A-3 修复] 加入 X-Internal-Token，与 Java P2-3 鉴权对齐
        req = urllib.request.Request(url, data=data, headers={
            'Content-Type': 'application/json',
            'X-Internal-Token': INTERNAL_TOKEN
        })
        with urllib.request.urlopen(req, timeout=5) as response:
            code = response.getcode()
            if code != 200:
                # 非 200 说明鉴权失败或服务异常，抛出异常以触发上层 ERROR 处理
                body = response.read().decode('utf-8', errors='ignore')
                raise RuntimeError(f"Callback rejected HTTP {code}: {body}")
    except Exception as e:
        print(f"⚠️ 无法回调 Java 端网络 ({task_id}): {e}")
        raise  # 上抛异常，让主循环记录 ERROR 状态

def post_log_report(file_code: str, status: int, error_msg: str, stats: dict = None):
    """
    带安全鉴权的第二通道：专项汇报底层耗时与成功分片量给 sys_file_parse_log 表。
    [A-4 修复] Token 不再硬编码，统一用环境变量 KB_INTERNAL_TOKEN。
    """
    try:
        url = f"{JAVA_API_BASE}/internal/logs/report"
        payload = {
            "token": INTERNAL_TOKEN,  # [A-4 修复] 不再硬编码
            "fileCode": file_code,
            "status": status
        }
        if error_msg:
            payload["parseResult"] = error_msg
        if stats:
            payload.update(stats)
            if not error_msg and stats.get("report") and "parseResult" not in payload:
                payload["parseResult"] = json.dumps({
                    "parseStatus": stats.get("parseStatus"),
                    "report": stats.get("report"),
                }, ensure_ascii=False)
            
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(url, data=data, headers={
            'Content-Type': 'application/json',
            'X-Internal-Token': INTERNAL_TOKEN   # [A-3 对齐] 日志接口也携带 Token
        })
        with urllib.request.urlopen(req, timeout=5) as response:
            if response.getcode() != 200:
                print(f"⚠️ 日志审计链路上报异常, Status: {response.getcode()}")
    except Exception as e:
        print(f"⚠️ 无法将提取度量送达审计后台 ({file_code}): {e}")


def post_operation_log(task_id: str, file_code: str, success: bool, cost_ms: int, error_msg: str = None):
    """
    写入操作日志到 sys_operation_log 表（RAG 文档入库任务专用）。

    业务功能：记录 task_worker 文档入库任务的处理结果到统一操作日志表，
             与 Java 侧日志共存，管理员可通过 service_name='ai_service' 过滤查看。
    关键方法：直接写 PostgreSQL（与 main.py middleware 共用同一 PG 连接参数）。
    流程说明：
        1. 构建日志字典，task_id 作为 trace_id（无 Java 侧 traceId 时独立标识任务）
        2. 连接 PostgreSQL 执行 INSERT（psycopg2，3s 超时）
        3. 任何异常仅打印警告，不影响主任务流程

    :param task_id:   任务 ID（作为 trace_id，便于关联 kibana/PG 查询）
    :param file_code: 文件代码（写入 request_uri 字段，便于定位具体文件）
    :param success:   任务是否成功
    :param cost_ms:   任务耗时（毫秒）
    :param error_msg: 异常信息（success=False 时传入）
    """
    import os as _os
    pg_host     = _os.getenv("PG_HOST",     "localhost")
    pg_port     = int(_os.getenv("PG_PORT", "5432"))
    pg_db       = _os.getenv("PG_DB",       "knowledge_base")
    pg_user     = _os.getenv("PG_USER",     "postgres")
    pg_password = _os.getenv("PG_PASSWORD", "")

    try:
        import psycopg2
        from datetime import datetime
        conn = psycopg2.connect(
            host=pg_host, port=pg_port, dbname=pg_db,
            user=pg_user, password=pg_password,
            connect_timeout=3
        )
        with conn:
            with conn.cursor() as cur:
                cur.execute("""
                    INSERT INTO sys_operation_log
                        (trace_id, service_name, module, operation, method, request_uri,
                         status_code, success, error_msg, cost_ms, user_id, created_at)
                    VALUES
                        (%s, 'ai_service', 'RAG文档处理', 'RAG文档入库', 'TASK', %s,
                         %s, %s, %s, %s, 'system', %s)
                """, (
                    task_id,
                    f"/task/rag-ingest/{file_code}",
                    200 if success else 500,
                    success,
                    error_msg[:2000] if error_msg else None,
                    cost_ms,
                    datetime.utcnow().isoformat()
                ))
        conn.close()
    except Exception as e:
        print(f"⚠️ [OperationLog] RAG任务日志写库失败 task_id={task_id}: {e}")


if __name__ == "__main__":
    main()
