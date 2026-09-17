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
from core.rag_pipeline import (
    AUXILIARY_INDEX_RETRY_MAX_ATTEMPTS,
    AUXILIARY_INDEX_RETRY_QUEUE,
    RAGPipeline,
    drain_due_java_callback_retries,
)
from core.normalization.text_normalizer import normalize_filename, normalize_metadata_text
from core.permissions.payload_projection import build_permission_projection_from_payload
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
QUEUE_RETRY = "DOC_TASK_RETRY"
QUEUE_QA = "QUEUE_QA"
QUEUE_QA_DLQ = "QUEUE_QA_DLQ"
QUEUE_QA_RETRY = "QUEUE_QA_RETRY"
DEFAULT_QA_ACL_TOKENS = ["_NO_ACCESS"]
MAX_RETRY  = 3  # 最大自动重试次数
RETRY_DELAY_SECONDS = int(os.getenv("DOC_TASK_RETRY_DELAY_SECONDS", "60"))
QA_RETRY_DELAY_SECONDS = int(os.getenv("QA_TASK_RETRY_DELAY_SECONDS", "60"))
AUXILIARY_RETRY_INDEXES = os.getenv("AUXILIARY_RETRY_INDEXES", "kb_doc_search,kb_doc_meta")


def _payload_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    return str(value).strip().lower() in ("1", "true", "yes", "y", "on")


def build_ext_meta_from_payload(payload: dict) -> dict:
    """
    业务功能：把 Java/Redis 任务 payload 转换为 RAGPipeline 入库所需的 ext_metadata。
    关键流程：统一清洗元数据、兼容 snake_case/camelCase 字段，并合并单位权限投影字段。
    设计原因：单任务入口和队列 E2E 必须共用同一套字段转换，避免权限字段在可测试入口丢失。
    """
    payload = payload or {}
    task_id = payload.get("taskId", "unknown_task")
    pub_time_raw = payload.get("publishTime")
    clean_pub_time = pub_time_raw if str(pub_time_raw or "").strip() else None
    ext_meta = {
        "tag": normalize_metadata_text(payload.get("tag")),
        "publish_time": clean_pub_time,
        "document_number": normalize_metadata_text(payload.get("docNumber")),
        "owner": normalize_metadata_text(payload.get("owner")),
        "search_queries": normalize_metadata_text(payload.get("searchQueries")),
        "visibility": payload.get("visibility", "INTERNAL"),
        "targetIndex": payload.get("targetIndex", ""),
        "dept_code": payload.get("deptCode", ""),
        "acl_tokens_json": payload.get("acl_tokens_json", ""),
        "grantedUserIds": payload.get("grantedUserIds", []),
        "grantedRoles": payload.get("grantedRoles", []),
        "uploader_id": payload.get("uploaderId", ""),
        "content_hash": payload.get("contentHash"),
        "full_hash": payload.get("full_hash", ""),
        "task_id": task_id,
        "docNumber": normalize_metadata_text(payload.get("docNumber")),
        "publishTime": clean_pub_time,
        "unit": normalize_metadata_text(payload.get("unit")),
        "skip_pages": int(payload.get("skip_pages", 0)),
        "scanned": _payload_bool(payload.get("scanned", False)),
        "force_ocr": _payload_bool(payload.get("force_ocr", payload.get("forceOcr", False))),
        "force_reindex": _payload_bool(payload.get("force_reindex", False)),
    }
    ext_meta.update(build_permission_projection_from_payload(payload))
    return ext_meta


def process_payload_once(payload: dict, pipeline: RAGPipeline, notify_java: bool = True) -> dict:
    """
    业务功能：处理单条文档入库任务，供 E2E 测试和后续 Worker 主循环收敛复用。
    关键流程：解析 payload -> 准备本地/HTML 文件 -> 构建权限元数据 -> 调用 RAGPipeline -> 可选回调 Java。
    设计原因：无限循环不利于自动化验证，单任务函数让 Redis 入队消费链路可以确定性执行和收口。
    """
    payload = payload or {}
    task_id = payload.get("taskId", "unknown_task")
    original_name = normalize_filename(payload.get("originalName", ""))
    file_path = payload.get("filePath", "")
    file_code = payload.get("fileCode", task_id)
    storage_mode = payload.get("storageMode", "MINIO")
    storage_path = payload.get("storagePath", "")
    temp_html_file = None
    task_start_time = time.time()

    try:
        html_content = payload.get("htmlContent", None)
        if html_content:
            import tempfile
            temp_html_file = tempfile.NamedTemporaryFile(suffix=".html", delete=False)
            temp_html_file.write(html_content.encode("utf-8", errors="ignore"))
            temp_html_file.close()
            file_path = temp_html_file.name
            original_name = original_name if original_name else f"db_export_{task_id}.html"

        source_name = original_name if original_name else os.path.basename(file_path)
        if storage_mode == "LOCAL_FS":
            print(f"📂 [DirectRead] 离线环境直读模式 -> {file_path}")
        else:
            print(f"📥 [Download] 远程下载模式 -> {source_name}")

        if file_path and not file_path.startswith(("http://", "https://")) and not os.path.exists(file_path):
            raise FileNotFoundError(
                "Worker cannot access filePath. "
                f"filePath={file_path}, storagePath={storage_path or '<empty>'}. "
                "Use a presigned URL/MinIO path or mount the same LOCAL_FS path into the AI container."
            )

        stats = pipeline.process_and_index(
            file_path,
            original_name=original_name,
            ext_metadata=build_ext_meta_from_payload(payload),
        )
        if (stats or {}).get("status") == "error":
            raise RuntimeError((stats or {}).get("message", "解析失败，质量门控拦截"))

        result_status = (stats or {}).get("status", "ok")
        if notify_java and result_status in ("skipped", "error"):
            try:
                import urllib.request as _urq
                import json as _jmod
                reg_url = f"{JAVA_API_BASE}/internal/doc/registry"
                reg_body = _jmod.dumps({
                    "taskId": task_id,
                    "sourceName": original_name or os.path.basename(file_path),
                    "docVersion": 0,
                    "fileBaseHash": "",
                    "chunkCount": 0,
                    "skipped": True,
                }).encode("utf-8")
                reg_req = _urq.Request(reg_url, data=reg_body, headers={
                    "Content-Type": "application/json",
                    "X-Internal-Token": INTERNAL_TOKEN,
                })
                with _urq.urlopen(reg_req, timeout=5):
                    pass
                print(f"ℹ️ [{task_id}] 文档 {result_status}，已通知 Java 关闭 outbox")
            except Exception as skip_err:
                print(f"⚠️ [{task_id}] 通知 Java 关闭 outbox 失败（可接受）: {skip_err}")

        if notify_java:
            parse_status = (stats or {}).get("parseStatus", "INDEXED")
            task_status = "INDEXED_PARTIAL" if parse_status == "INDEXED_PARTIAL" else "INDEXED"
            post_callback(task_id, task_status, "")
            post_log_report(file_code, 1, "", stats)
            cost_ms = int((time.time() - task_start_time) * 1000)
            post_operation_log(task_id, file_code, True, cost_ms)

        print(f"✅ 任务完成流转 -> TaskID: {task_id}")
        return stats or {}
    finally:
        if temp_html_file and os.path.exists(temp_html_file.name):
            try:
                os.remove(temp_html_file.name)
            except Exception as clean_err:
                print(f"⚠️ 清理临时文件异常 {temp_html_file.name}: {clean_err}")


def consume_one_payload_once(redis_client, pipeline: RAGPipeline, timeout: int = 5, notify_java: bool = True) -> dict | None:
    """
    业务功能：从正式 Worker 队列消费一条任务并执行一次，执行后立即返回。
    关键流程：按生产优先级监听 HIGH -> LOW，取到 payload 后调用 process_payload_once。
    设计原因：为 Redis 入队消费 E2E 提供确定性入口，不启动不可收口的无限循环。
    """
    result = redis_client.brpop([QUEUE_HIGH, QUEUE_LOW], timeout=timeout)
    if not result:
        return None
    _, payload_str = result
    payload = json.loads(payload_str)
    return process_payload_once(payload, pipeline, notify_java=notify_java)


def enqueue_retry_payload(redis_client, payload: dict, delay_seconds: int = RETRY_DELAY_SECONDS) -> float:
    """
    业务功能：把失败任务放入 Redis 延迟重试集合，等待到期后再回到正式低优先队列。
    关键流程：计算下一次可重试时间戳 -> 写入 DOC_TASK_RETRY sorted set。
    设计原因：主 Worker 同时监听低优先队列，直接 LPUSH 会导致同一失败任务被立刻再次消费并快速打入死信。
    """
    retry_at = time.time() + max(0, delay_seconds)
    redis_client.zadd(QUEUE_RETRY, {json.dumps(payload, ensure_ascii=False): retry_at})
    return retry_at


def drain_due_retry_payloads(redis_client, now: float | None = None, limit: int = 100) -> int:
    """
    业务功能：把已经到期的延迟重试任务搬回低优先队列，供正常 Worker 消费。
    关键流程：按 score 查询到期任务 -> 原子移除成功后 LPUSH 到 DOC_TASK_QUEUE。
    设计原因：延迟集合只负责等待，真正执行仍复用原有高/低优先队列，避免引入额外 Worker 角色。
    """
    now = time.time() if now is None else now
    due_items = redis_client.zrangebyscore(QUEUE_RETRY, 0, now, start=0, num=limit)
    moved = 0
    for item in due_items:
        if redis_client.zrem(QUEUE_RETRY, item):
            redis_client.lpush(QUEUE_LOW, item)
            moved += 1
    return moved


def enqueue_qa_retry_payload(redis_client, payload: dict, delay_seconds: int = QA_RETRY_DELAY_SECONDS) -> float:
    """
    业务功能：把失败 QA 任务放入 Redis 延迟重试集合。
    关键流程：计算下一次可重试时间戳 -> 写入 QUEUE_QA_RETRY sorted set。
    设计原因：内嵌 QA Worker 与独立 QA Worker 必须使用同一失败调度语义，避免 LLM/ES 短暂失败时热循环。
    """
    retry_at = time.time() + max(0, delay_seconds)
    redis_client.zadd(QUEUE_QA_RETRY, {json.dumps(payload, ensure_ascii=False): retry_at})
    return retry_at


def drain_due_qa_retry_payloads(redis_client, now: float | None = None, limit: int = 100) -> int:
    """
    业务功能：把到期 QA 重试任务搬回 QUEUE_QA。
    关键流程：查询到期 sorted set 元素，成功移除后写回正式 QA 队列。
    设计原因：延迟集合只负责等待，真正执行仍复用 QUEUE_QA，降低运行模型复杂度。
    """
    now = time.time() if now is None else now
    due_items = redis_client.zrangebyscore(QUEUE_QA_RETRY, 0, now, start=0, num=limit)
    moved = 0
    for item in due_items:
        if redis_client.zrem(QUEUE_QA_RETRY, item):
            redis_client.lpush(QUEUE_QA, item)
            moved += 1
    return moved


def drain_due_auxiliary_index_retries(redis_client, pipeline: RAGPipeline,
                                      now: float | None = None, limit: int = 20) -> int:
    """
    业务功能：消费到期的辅助索引补偿任务，按 source 修复 kb_doc_search/kb_doc_meta 权限投影。
    关键流程：从 Redis sorted set 取到期任务 -> 调用离线补齐函数定向修复 -> 成功移除，失败重新入队。
    设计原因：辅助索引是可从 chunk 事实源重建的性能索引，失败后应最终一致，不应只依赖人工全量脚本。
    """
    from scripts.backfill_auxiliary_permission_projection import backfill_source

    now = time.time() if now is None else now
    due_items = redis_client.zrangebyscore(AUXILIARY_INDEX_RETRY_QUEUE, 0, now, start=0, num=limit)
    repaired = 0
    for raw in due_items:
        if not redis_client.zrem(AUXILIARY_INDEX_RETRY_QUEUE, raw):
            continue
        text = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        try:
            item = json.loads(text)
            source_name = item.get("sourceName") or item.get("source")
            if not source_name:
                continue
            indexes = [v.strip() for v in AUXILIARY_RETRY_INDEXES.split(",") if v.strip()]
            stats = backfill_source(
                es=pipeline.es,
                source_name=source_name,
                aux_indexes=indexes,
                chunk_index=os.getenv("KB_DOCUMENT_READ_ALIAS", "kb_document"),
                execute=True,
            )
            repaired += int(stats.written or 0)
            print(f"[AuxIndexRetry] repaired source={source_name} written={stats.written} unresolved={stats.skipped_unresolved}")
        except Exception as exc:
            try:
                item = json.loads(text)
            except Exception:
                item = {"sourceName": "", "targetIndex": ""}
            attempt = int(item.get("attempt") or 0) + 1
            item["attempt"] = attempt
            item["lastError"] = str(exc)[:1000]
            if attempt >= AUXILIARY_INDEX_RETRY_MAX_ATTEMPTS:
                item["lastError"] = f"max attempts reached: {exc}"[:1000]
                redis_client.lpush(f"{AUXILIARY_INDEX_RETRY_QUEUE}_DLQ", json.dumps(item, ensure_ascii=False))
                print(f"[AuxIndexRetry] moved to DLQ source={item.get('sourceName')} err={exc}")
                continue
            retry_at = time.time() + int(os.getenv("AUXILIARY_INDEX_RETRY_DELAY_SECONDS", "60"))
            item["retryAt"] = retry_at
            redis_client.zadd(AUXILIARY_INDEX_RETRY_QUEUE, {json.dumps(item, ensure_ascii=False): retry_at})
            print(f"[AuxIndexRetry] repair failed source={item.get('sourceName')} err={exc}")
    return repaired


def process_payload_with_retry(redis_client, payload: dict, pipeline: RAGPipeline, notify_java: bool = True) -> str:
    """
    业务功能：处理单条 payload，并在失败时按 Worker 规则写入延迟重试集合或死信队列。
    关键流程：调用 process_payload_once，失败后检查 retryCount，小于阈值写入 DOC_TASK_RETRY，达到阈值写入 DLQ。
    设计原因：失败编排必须可测试，否则权限入库链路只验证成功路径，真实异常时可能出现任务丢失或无限重试。
    """
    payload = payload or {}
    task_id = payload.get("taskId", "unknown_task")
    file_code = payload.get("fileCode", task_id)
    task_start_time = time.time()
    try:
        process_payload_once(payload, pipeline, notify_java=notify_java)
        return "DONE"
    except Exception as e:
        import traceback
        error_msg = traceback.format_exc()
        print(f"?任务执行中断 -> TaskID: {task_id}, Error: {str(e)}")

        retry_count = payload.get("retryCount", 0)
        if retry_count < MAX_RETRY:
            payload["retryCount"] = retry_count + 1
            payload["lastError"] = str(e)[:500]
            retry_at = enqueue_retry_payload(redis_client, payload)
            print(f"♻️ [{task_id}] 处理失败（第 {retry_count + 1} 次），已进入延迟重试队列，retryAt={int(retry_at)}")
            return "RETRY"

        redis_client.lpush(QUEUE_DLQ, json.dumps(payload, ensure_ascii=False))
        if notify_java:
            post_callback(task_id, "ERROR", str(e))
            post_log_report(file_code, 2, error_msg)
            task_cost_ms = int((time.time() - task_start_time) * 1000)
            post_operation_log(task_id, file_code, False, task_cost_ms, str(e)[:500])
        print(f"💀 [{task_id}] 达到最大重试次数({MAX_RETRY})，已转入死信队列 {QUEUE_DLQ}")
        return "DLQ"


def consume_one_payload_with_retry(redis_client, pipeline: RAGPipeline, timeout: int = 5, notify_java: bool = True) -> str | None:
    """
    业务功能：从正式 Worker 队列消费一条任务，并执行包含重试/DLQ 编排的单次处理。
    关键流程：按 HIGH -> LOW 优先级取任务，交给 process_payload_with_retry，执行后返回 DONE/RETRY/DLQ。
    设计原因：让失败路径也能像成功路径一样通过自动化 E2E 验证，而不是依赖无限主循环人工观察。
    """
    result = redis_client.brpop([QUEUE_HIGH, QUEUE_LOW], timeout=timeout)
    if not result:
        return None
    _, payload_str = result
    payload = json.loads(payload_str)
    return process_payload_with_retry(redis_client, payload, pipeline, notify_java=notify_java)


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
        qa_redis   = _make_redis()
        _payload   = {}
        print(f"📋 [QA Worker] 内嵌线程启动，监听 [{QUEUE_QA}]...")
        while True:
            try:
                drain_due_qa_retry_payloads(qa_redis)
                result = qa_redis.brpop([QUEUE_QA], timeout=30)
                if not result:
                    continue
                _, raw   = result
                _payload    = json.loads(raw)
                task_id     = _payload.get("taskId", "unknown")
                source_name = _payload.get("sourceName", "")
                fine_chunks = _payload.get("finChunks", [])
                acl_tokens  = _payload.get("acl_tokens", DEFAULT_QA_ACL_TOKENS)
                file_hash   = _payload.get("fileBaseHash", "")
                target_index = _payload.get("targetIndex", "")
                doc_version  = _payload.get("docVersion", 0)
                qa_meta = build_permission_projection_from_payload(_payload)

                if not fine_chunks:
                    continue

                print(f"📋 [QA Worker] 处理 taskId={task_id} chunks={len(fine_chunks)}")
                pipeline._generate_and_index_qa_pairs(
                    fine_chunks=fine_chunks,
                    source_name=source_name,
                    file_base_hash=file_hash,
                    acl_tokens=acl_tokens,
                    doc_version=doc_version,
                    source_index=target_index,
                    ext_metadata=qa_meta,
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
                        _payload["lastError"] = str(_qe)[:500]
                        _retry_at = enqueue_qa_retry_payload(qa_redis, _payload)
                        print(f"🔄 [QA Worker] 任务进入延迟重试，第 {_retry + 1} 次 taskId={_payload.get('taskId', '?')} retryAt={int(_retry_at)}")
                    else:
                        qa_redis.lpush(QUEUE_QA_DLQ, json.dumps(_payload, ensure_ascii=False))
                        print(f"💀 [QA Worker] 达到最大重试次数，转入 {QUEUE_QA_DLQ}")
                except Exception:
                    pass

    threading.Thread(target=_qa_worker_loop, daemon=True, name="qa-worker").start()

    # 同时监听两个队列：HIGH 在前表示优先级更高（brpop 按参数顺序依次检查）
    print(f"🚀 开始阻塞监听队列: {QUEUE_HIGH} (高优先) → {QUEUE_LOW} (标准)")
    while True:

        try:
            drain_due_java_callback_retries(redis_client)
            drain_due_auxiliary_index_retries(redis_client, pipeline)
            drain_due_retry_payloads(redis_client)
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
            # 入库与失败编排都收敛到可测试入口，避免主循环与 E2E 逻辑分叉。
            process_payload_with_retry(redis_client, payload, pipeline, notify_java=True)
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
