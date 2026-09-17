"""
task_worker_qa.py — 独立 QA 知识库生成 Worker 进程。

业务功能：消费 QUEUE_QA 队列中的任务，为文档片段批量生成问答对（QA Pairs），
          并写入 kb_qa_pairs 索引（通过 kb_qa_write 别名写入，支持零停机 Reindex），
          供知识库问答检索使用。

系统解耦设计（P1-B）：
  - 主 task_worker.py：负责文档解析、分块、向量化、ES 写入（高优先级）
  - task_worker_qa.py（本文件）：负责 QA 生成（低优先级、IO密集、可独立扩容）

  解耦原因（第一性原理分析）：
    ① QA 生成调用大模型，响应时间长（5-30s/任务），严重阻塞主 Worker 流响应
    ② QA 生成失败不影响文档检索，可独立重试/降级，不应污染主流程错误计数
    ③ 独立进程可独立扩容（主 Worker 1实例 vs QA Worker 多实例）

队列格式（QUEUE_QA payload）：
  {
    "taskId"     : "uuid",
    "sourceName" : "report.pdf",
    "chunkCount" : 42,
    "acl_tokens" : ["_NO_ACCESS"],
    "finChunks"  : [
      {"chunk_id": "x", "content": "...", "metadata": {...}},
      ...
    ]
  }
"""

import os
import json
import time
import redis
from dotenv import load_dotenv
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.append(BASE_DIR)
from core.permissions.payload_projection import build_permission_projection_from_payload
load_dotenv()

REDIS_HOST     = os.getenv("REDIS_HOST", "redis")
REDIS_PORT     = int(os.getenv("REDIS_PORT", 6379))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")
JAVA_API_BASE  = os.getenv("JAVA_API_BASE", "http://knowledge-base-java:8080/api/v1")
INTERNAL_TOKEN = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")

# QA 专用队列（task_worker 推入，本 Worker 消费）
QUEUE_QA   = "QUEUE_QA"
QUEUE_QADL = "QUEUE_QA_DLQ"   # QA 死信队列
QUEUE_QARETRY = "QUEUE_QA_RETRY"
DEFAULT_QA_ACL_TOKENS = ["_NO_ACCESS"]
MAX_RETRY  = 3
QA_RETRY_DELAY_SECONDS = int(os.getenv("QA_TASK_RETRY_DELAY_SECONDS", "60"))


def _make_redis() -> redis.Redis:
    """
    业务功能：建立带 TCP Keepalive 的 Redis 连接。
    与主 task_worker 参数完全一致，避免双份配置漂移。
    """
    kwargs = {
        "host": REDIS_HOST,
        "port": REDIS_PORT,
        "decode_responses": True,
        "socket_keepalive": True,
        "socket_connect_timeout": 5,
        "health_check_interval": 25,
        "retry_on_timeout": True,
    }
    if REDIS_PASSWORD:
        kwargs["password"] = REDIS_PASSWORD
    return redis.Redis(**kwargs)


def _init_qa_pipeline():
    """
    延迟初始化 QA Pipeline（避免启动时即加载大模型，浪费内存）。
    QA Pipeline 负责：
      1. 调用 LLM 为 fine chunk 批量生成 [问题, 答案] 对
      2. 计算问答对向量
      3. 写入 kb_qa_pairs ES 索引
    """
    try:
        from core.rag_pipeline import RAGPipeline
        pipeline = RAGPipeline()
        print("✅ [QA Worker] RAGPipeline 初始化成功")
        return pipeline
    except Exception as e:
        print(f"❌ [QA Worker] RAGPipeline 初始化失败: {e}")
        raise


def enqueue_qa_retry_payload(redis_client, payload: dict, delay_seconds: int = QA_RETRY_DELAY_SECONDS) -> float:
    """
    业务功能：把失败的 QA 任务放入 Redis 延迟重试集合。
    关键流程：计算下一次可重试时间戳，写入 QUEUE_QA_RETRY sorted set。
    设计原因：QA 生成依赖 LLM/ES，直接 lpush 回 QUEUE_QA 会被当前 Worker 立即再次消费，形成热循环。
    """
    retry_at = time.time() + max(0, delay_seconds)
    redis_client.zadd(QUEUE_QARETRY, {json.dumps(payload, ensure_ascii=False): retry_at})
    return retry_at


def drain_due_qa_retry_payloads(redis_client, now: float | None = None, limit: int = 100) -> int:
    """
    业务功能：把到期 QA 重试任务搬回正式 QUEUE_QA 队列。
    关键流程：按 score 查询到期任务，成功 zrem 后 lpush 回 QUEUE_QA。
    设计原因：等待和执行解耦，既保留原消费模型，又避免失败任务立刻自旋。
    """
    now = time.time() if now is None else now
    due_items = redis_client.zrangebyscore(QUEUE_QARETRY, 0, now, start=0, num=limit)
    moved = 0
    for item in due_items:
        if redis_client.zrem(QUEUE_QARETRY, item):
            redis_client.lpush(QUEUE_QA, item)
            moved += 1
    return moved


def main():
    print("⏳ [QA Worker] 初始化 Redis 客户端...")
    redis_client = _make_redis()

    try:
        redis_client.ping()
        print(f"✅ [QA Worker] Redis 连接成功 ({REDIS_HOST}:{REDIS_PORT})")
    except Exception as e:
        print(f"❌ [QA Worker] Redis 连接失败: {e}")
        sys.exit(1)

    print("⏳ [QA Worker] 初始化 QA Pipeline...")
    pipeline = _init_qa_pipeline()

    print(f"🚀 [QA Worker] 开始监听队列 [{QUEUE_QA}]...")

    while True:
        try:
            drain_due_qa_retry_payloads(redis_client)
            # 阻塞消费，超时 5s 后循环重试（防止 Redis 连接被 NAT 設备超时关闭）
            result = redis_client.brpop([QUEUE_QA], timeout=5)
            if result is None:
                continue

            _, raw = result
            payload = json.loads(raw)
            task_id    = payload.get("taskId", "unknown")
            source_name = payload.get("sourceName", "unknown")
            fine_chunks = payload.get("finChunks", [])
            acl_tokens  = payload.get("acl_tokens", DEFAULT_QA_ACL_TOKENS)
            file_base_hash = payload.get("fileBaseHash", "")
            # [版本化] 从 payload 提取 doc_version，0 为降级兜底（旧消息无此字段时不影响写入，但 2PC 无法切换旧版）
            doc_version = payload.get("docVersion", 0)
            target_index = payload.get("targetIndex", "")
            qa_meta = build_permission_projection_from_payload(payload)

            print(f"📋 [QA Worker] 消费任务 taskId={task_id} source={source_name} chunks={len(fine_chunks)}")

            if not fine_chunks:
                print(f"⚠️ [QA Worker] finChunks 为空，跳过 taskId={task_id}")
                continue

            try:
                # 调用 RAGPipeline 生成并写入 QA 对
                # _generate_and_index_qa_pairs 内部处理：LLM 生成 → 向量化 → ES bulk_write
                pipeline._generate_and_index_qa_pairs(
                    fine_chunks=fine_chunks,
                    source_name=source_name,
                    file_base_hash=file_base_hash,
                    acl_tokens=acl_tokens,
                    doc_version=doc_version,   # [版本化] 透传版本号，写入 ES doc_version 字段
                    source_index=target_index,
                    ext_metadata=qa_meta,
                )
                print(f"✅ [QA Worker] 任务完成 taskId={task_id} source={source_name}")

            except Exception as e:
                import traceback
                error_msg = traceback.format_exc()
                print(f"❌ [QA Worker] 任务失败 taskId={task_id} err={str(e)}")

                retry_count = payload.get("retryCount", 0)
                if retry_count < MAX_RETRY:
                    payload["retryCount"] = retry_count + 1
                    payload["lastError"] = str(e)[:500]
                    retry_at = enqueue_qa_retry_payload(redis_client, payload)
                    print(f"🔄 [QA Worker] 任务进入延迟重试，第 {retry_count + 1} 次 taskId={task_id} retryAt={int(retry_at)}")
                else:
                    redis_client.lpush(QUEUE_QADL, json.dumps(payload, ensure_ascii=False))
                    print(f"💀 [QA Worker] 达到最大重试次数({MAX_RETRY})，转入死信队列 taskId={task_id}")

        except redis.exceptions.ConnectionError as conn_err:
            print(f"⚠️ [QA Worker] Redis 断连，3秒后重连: {conn_err}")
            time.sleep(3)
            try:
                redis_client = _make_redis()
                redis_client.ping()
                print("✅ [QA Worker] Redis 重连成功")
            except Exception as re_err:
                print(f"❌ [QA Worker] Redis 重连失败，10s 后再试: {re_err}")
                time.sleep(10)
        except Exception as system_err:
            print(f"⚠️ [QA Worker] 系统级故障: {system_err}")
            time.sleep(5)


if __name__ == "__main__":
    main()
