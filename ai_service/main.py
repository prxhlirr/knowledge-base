import time
import os
import re

# 优先加载 .env 文件中的环境变量（离线部署配置入口）
# 开发环境无 .env 时自动跳过，不影响已通过 os.environ 设置的变量
try:
    from dotenv import load_dotenv
    # 容器部署：优先加载 /app/config/.env（宿主机 volume 挂载，可手动编辑）
    # 本地开发：fallback 到同目录 .env 文件
    _container_env = "/app/config/.env"
    _local_env = os.path.join(os.path.dirname(__file__), ".env")
    _env_path = _container_env if os.path.exists(_container_env) else _local_env
    load_dotenv(dotenv_path=_env_path)
    print(f"[Config] Loaded env from: {_env_path}")
except ImportError:
    pass  # 未安装 python-dotenv 时静默跳过

import requests as req_lib
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
from contextlib import asynccontextmanager
from core.model_manager import model_manager
from core.llm_client import LLMClient
# from core.chunker import GovDocChunker
import uvicorn
import numpy as np
from typing import List, Optional, Dict

from core.ltr_manager import LTRManager

ltr_manager = LTRManager()


import threading
from task_worker import main as run_worker

@asynccontextmanager
async def lifespan(app: FastAPI):
    if os.getenv("AI_MODEL_PRELOAD", "false").lower() == "true":
        print("🚀 [Lifespan] AI_MODEL_PRELOAD=true，主动预加载 AI 模型层 (Embedding & Reranker)...")
        model_manager.load_model()
    else:
        print("ℹ️ [Lifespan] AI_MODEL_PRELOAD=false，模型将在首次 encode/rerank 时懒加载")

    # [冷启动优化] 预热 Jieba 结巴分词字典，消除第一个文档处理时的 0.5-1s 懒加载延迟。
    # 根因：Jieba 首次调用 extract_tags 时才从磁盘加载 prefix/suffix dict（约 0.635s），
    #       之后常驻内存。提前在 lifespan 触发一次，所有文档均可直接命中缓存。
    try:
        import jieba.analyse as _jieba_analyse
        _jieba_analyse.extract_tags("预热结巴分词字典初始化", topK=5)
        print("✅ [Lifespan] Jieba 字典预热完成")
    except Exception as _je:
        print(f"⚠️ [Lifespan] Jieba 预热失败（不影响主流程）: {_je}")


    def _model_idle_watchdog():
        if os.getenv("AI_MODEL_IDLE_UNLOAD", "true").lower() != "true":
            print("[ModelIdle] AI_MODEL_IDLE_UNLOAD=false，跳过模型空闲卸载监控")
            return
        ttl = int(os.getenv("AI_MODEL_IDLE_TTL_SECONDS", "1800"))
        interval = int(os.getenv("AI_MODEL_IDLE_CHECK_INTERVAL_SECONDS", "60"))
        print(f"[ModelIdle] 启动模型空闲卸载监控 ttl={ttl}s interval={interval}s")
        while True:
            try:
                status = model_manager.get_health_status()
                loaded = status.get("embedding_loaded") or status.get("reranker_loaded")
                idle = float(status.get("idle_seconds") or 0)
                if loaded and idle >= ttl:
                    print(f"[ModelIdle] 模型空闲 {idle:.0f}s >= {ttl}s，执行 unload_all()")
                    model_manager.unload_all()
            except Exception as _e:
                print(f"⚠️ [ModelIdle] 空闲卸载监控异常: {_e}")
            time.sleep(interval)

    threading.Thread(target=_model_idle_watchdog, daemon=True, name="model-idle-watchdog").start()

    def _worker_watchdog():
        """
        业务功能：用守护线程包裹 task_worker.main()，确保 worker 崩溃后自动重启。
        根因说明：task_worker 在初始化 RAGPipeline 时若 ES 短暂不可用会抛出异常退出，
                  导致 Redis 队列消息永远积压、文档永远 PENDING。
        修复方案：线程崩溃后等待 10s 重试，最多重试 100 次（即约 1000s 内持续尝试）。
                  每次崩溃都打印 WARN 日志而非静默失败。
        """
        max_restarts = 100
        restart_count = 0
        while restart_count < max_restarts:
            try:
                print(f"🚀 [Lifespan] 启动 Redis 文档转化队列监听线程 (第 {restart_count + 1} 次)...")
                run_worker()
                # run_worker 正常退出（理论上永远不会，因为主循环是 while True）
                print("[Watchdog] task_worker 已正常退出，5s 后重启...")
            except Exception as _e:
                print(f"⚠️ [Watchdog] task_worker 线程崩溃（第 {restart_count + 1} 次），10s 后重启: {_e}")
            restart_count += 1
            time.sleep(10)
        print("❌ [Watchdog] task_worker 连续重启次数已达上限，停止自动重启。请检查服务配置。")

    print("🚀 [Lifespan] 启动 Redis 文档转化队列监听后台线程（含 Watchdog 守护重启）...")
    threading.Thread(target=_worker_watchdog, daemon=True, name="task-worker-watchdog").start()

    yield
    print("🛑 [Lifespan] 服务关闭清理资源...")

app = FastAPI(title="Boyang AI Service", lifespan=lifespan)

# [操作日志] 注册操作日志中间件，自动拦截所有接口并异步写入 sys_operation_log 表
# 通过 X-Trace-Id Header 与 Java 侧日志串联（Java 调用 AI 时透传 traceId）
from core.operation_log_middleware import OperationLogMiddleware
app.add_middleware(OperationLogMiddleware)


# --- NLP 模型 (HanLP / Pinyin) 初始化 ---
# 这里封装一套标准的 NLP 处理工具，用于查询词纠错、拼音提取、繁简转换
class NLPProcessor:
    def __init__(self):
        try:
            import opencc
            self.cc = opencc.OpenCC('t2s')
            from pypinyin import pinyin, Style
            self.pypinyin = pinyin
            self.style = Style
        except ImportError:
            self.cc = None
            print("⚠️ NLP dependencies (opencc/pypinyin) not found. Run: pip install opencc-python-reimplemented pypinyin")
            
        # 预热 pypinyin 庞大的字典缓存，防止首条查询由于懒加载卡住 3 秒触发 Java 熔断
        if getattr(self, 'pypinyin', None):
            self.get_pinyin("预热字典专用句子")

    def normalize(self, text: str):
        if not text: return ""
        # 1. 繁转简
        res = self.cc.convert(text) if self.cc else text
        # 2. 全角转半角 (简单实现)
        res = "".join([chr(ord(c) - 65248) if 65281 <= ord(c) <= 65374 else c for c in res])
        return res

    def get_pinyin(self, text: str):
        if not self.pypinyin: return ""
        py_list = self.pypinyin(text, style=self.style.FIRST_LETTER)
        return "".join([i[0] for i in py_list])

nlp_proc = NLPProcessor()

# --- 请求体定义 ---
class NormalizeRequest(BaseModel):
    text: str

class QueryRequest(BaseModel):
    text: str
    skip_instruction: Optional[bool] = False

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

class DocumentSimilarityRerankRequest(BaseModel):
    query: str
    documents: List[str]

class SentenceScoresRequest(BaseModel):
    query: str
    sentences: List[str]

class SimilarityCompareRequest(BaseModel):
    text_a: str
    text_b: str

class LTRRankRequest(BaseModel):
    features: List[Dict]

# --- 接口实现 ---

@app.post("/api/ai/nlp/normalize")
def nlp_normalize(req: NormalizeRequest):
    """
    业务功能：对用户输入的原始查询词进行标准化清洗（繁简、全半角、拼音提取）
    """
    normalized = nlp_proc.normalize(req.text)
    pinyin = nlp_proc.get_pinyin(normalized)
    return {
        "code": 200,
        "msg": "success",
        "data": {
            "original": req.text,
            "normalized": normalized,
            "pinyin": pinyin
        }
    }

from functools import lru_cache

# BGE-M3 官方推荐的不对称检索指令前缀：专为「短 query vs 长 passage」场景设计
# 实测可将 14 字短 query 的向量相似度从 0.21 提升至 0.35~0.45
BGE_QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: "

@lru_cache(maxsize=1024)
def encode_query_cached(text: str, skip_instruction: bool = False):
    """
    带缓存的向量化编码。
    [P0 核心修复] 移除原来的 len(text) <= 15 限制。
    第一性原理：BGE-M3 的不对称检索设计要求 Query 侧必须带指令才能与 Passage 空间对齐。
    原代码中 <=15 字不加指令会导致短查询分数剧降 (0.7 -> 0.3)，造成检索失效。
    """
    if skip_instruction:
        return model_manager.encode(text)
    # 只要是检索场景，无论长短，一律加上不对称检索指令
    return model_manager.encode(BGE_QUERY_INSTRUCTION + text)

@app.post("/api/ai/vector/query")
def encode_query(req: QueryRequest):
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="Query text cannot be empty")
        
    start_time = time.time()
    try:
        # 显式传递 skip_instruction 参数，默认为 False (由 API 定义控制)
        vectors = encode_query_cached(req.text, skip_instruction=req.skip_instruction)
        cost_ms = int((time.time() - start_time) * 1000)
        
        if not vectors or len(vectors) == 0:
            print(f"❌ [QueryVector] Failed to generate embedding: vectors is empty. Query: '{req.text[:20]}'")
            raise HTTPException(status_code=500, detail="Embedding process failed: empty result")

        print(f"🔍 [QueryVector] Text: '{req.text[:20]}...' | Cost: {cost_ms}ms")
        
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "vector": vectors[0] if vectors else [],
                "costMs": cost_ms
            }
        }
    except HTTPException:
        raise
    except Exception as e:
        print(f"❌ [QueryVector] Internal Error: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))


# 稀疏向量查询缓存（LRU，最大 512 条，避免单次查询重复推理）
from functools import lru_cache as _lru_cache

@_lru_cache(maxsize=512)
def _encode_sparse_cached(text: str) -> str:
    """稀疏向量带缓存编码，返回 JSON 字符串（lru_cache 要求 hashable 返回值）"""
    import json
    result = model_manager.encode_sparse(text, top_k=64)
    return json.dumps(result[0] if result else {}, ensure_ascii=False)


@app.post("/api/ai/vector/sparse")
def encode_sparse_query(req: QueryRequest):
    """
    业务功能：返回查询文本的 BGE-M3 稀疏向量（SPLADE 风格），兼容 ES rank_features 格式。
    核心原理：
      调用 model_manager.encode_sparse()，提取每个 token 的 ReLU 激活权重，
      按词聚合（同词取最大值 SPLADE-Max），保留 top-64 个高权重词，
      返回 {token_str: weight} 字典，可直接写入 ES sparse_vector 字段
      或用于构造 rank_features query。
    用途：
      1. 入库时：rag_pipeline 调用此接口，将 sparse_vector 写入 ES（激活已有 rank_features mapping）
      2. 查询时：Java SearchService 调用此接口，构造 rank_features query，与 BM25+KNN 融合
    性能：单条约 30-50ms（复用 encode_colbert 的 last_hidden_state，无额外模型权重）
    降级：encode_sparse 失败时返回空字典 {}，不影响主检索链路。
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="Query text cannot be empty")

    start_time = time.time()
    try:
        import json
        sparse_json = _encode_sparse_cached(req.text)
        sparse_weights = json.loads(sparse_json)
        cost_ms = int((time.time() - start_time) * 1000)

        print(f"🔢 [SparseVector] Text: '{req.text[:20]}' | Terms: {len(sparse_weights)} | Cost: {cost_ms}ms")

        return {
            "code": 200,
            "msg": "success",
            "data": {
                "sparse_vector": sparse_weights,
                "term_count": len(sparse_weights),
                "costMs": cost_ms
            }
        }
    except HTTPException:
        raise
    except Exception as e:
        print(f"❌ [SparseVector] Error: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/ai/vector/dual")
def encode_dual_vector(req: QueryRequest):
    """
    业务功能：单次请求同时返回 dense（稠密）+ sparse（稀疏）双模向量。
    [P0 修复] 原实现是"假 dual"：内部调用 encode_query_cached + _encode_sparse_cached，
    实质是两次独立 ONNX forward pass（每次约 150~250ms），总耗时 ~400ms。
    修复方案：直接调用 model_manager.encode_dual()，BGE-M3 单次 forward pass 同时输出
    sentence_embedding（dense CLS 向量）和 last_hidden_state（sparse 权重来源），
    节省一整次 GPU 推理，理论降至 ~200ms。
    指令前缀逻辑与 encode_query_cached 完全对齐（≤15字无前缀，>15字加 BGE 指令），
    encode_dual 内部已将 dense 写入 model_manager._embedding_cache，
    后续同文本的 /vector/query 调用将命中内部缓存（0ms 额外开销）。
    降级路径：encode_dual 内部任何异常自动回退到两次独立调用，接口层有 try/except 兜底。
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="Query text cannot be empty")

    start_time = time.time()
    try:
        text = req.text.strip()
        # 与 encode_query_cached 保持相同的 BGE 不对称检索指令前缀决策：
        # [P0 核心修复] 移除 15 字限制，改为由请求参数 skip_instruction 控制
        # 默认为 False，即：检索场景默认开启指令增强，除非明确要求 Doc-to-Doc 对称匹配
        actual_text = text if req.skip_instruction else BGE_QUERY_INSTRUCTION + text

        # 单次 ONNX 推理同时取 dense + sparse（P0 核心修复点）
        # encode_dual 已在 model_manager.py 中实现并在入库流程中使用，此处补全查询侧调用
        dense_vecs, sparse_vecs = model_manager.encode_dual(actual_text, top_k=64)
        dense_vector  = dense_vecs[0] if dense_vecs else []
        sparse_vector = sparse_vecs[0] if sparse_vecs else {}

        cost_ms = int((time.time() - start_time) * 1000)
        print(f"⚡ [DualVector] Text: '{req.text[:20]}' | Dense:{len(dense_vector)}d | Sparse:{len(sparse_vector)}terms | Cost:{cost_ms}ms")

        return {
            "code": 200,
            "msg": "success",
            "data": {
                "dense_vector":  dense_vector,
                "sparse_vector": sparse_vector,
                "costMs":        cost_ms
            }
        }
    except HTTPException:
        raise
    except Exception as e:
        print(f"❌ [DualVector] Error: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/ai/vector/long-doc")
def encode_long_doc(req: QueryRequest):
    """
    业务功能：对超长文本（文档级）做均值池化向量化，返回文档级 doc_vector。
    核心原理：BGE-M3 输入长度限制约 512 token（约 350-400 中文字），超长文本无法直接编码。
             将长文本按 300 字滑动窗口分段，对每段独立 encode 得到 1024 维向量，
             最后对所有分段向量做均值池化，得到能代表整篇文档的 doc_vector。
    关键流程：text → 分段(300字/段,50字重叠) → 各段 encode → NumPy mean pooling → 返回 doc_vector
    适用场景：
      - Phase 3 批量脚本调用：为每个文档的所有 chunk 文本计算文档级向量
      - 相似文档搜索：用户输入一段文本寻找相似文档时的向量化入口
    与 /api/ai/vector/query 的区别：
      - query 接口：短文本（≤512 token），直接编码，有指令前缀
      - long-doc 接口：长文本，分段均值池化，无指令前缀（文档vs文档语义空间）

    @param req.text 文档原文（可超 512 token）
    @return { code, data: { vector: [1024 floats], segments: int, costMs: int } }
    """
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text cannot be empty")

    start_time = time.time()
    try:
        # 分段参数：300 字/段，50 字重叠，防止边界语义截断
        SEGMENT_SIZE = 300
        OVERLAP = 50
        
        # 构建分段列表
        segments = []
        if len(text) <= SEGMENT_SIZE:
            segments = [text]
        else:
            i = 0
            while i < len(text):
                end = min(i + SEGMENT_SIZE, len(text))
                segments.append(text[i:end])
                if end == len(text):
                    break
                i += SEGMENT_SIZE - OVERLAP  # 滑动窗口
        
        # 对每段 encode（批量传入提速）
        all_vectors = np.array(model_manager.encode(segments))  # shape: (N, 1024)
        
        if all_vectors is None or len(all_vectors) == 0:
            raise HTTPException(status_code=500, detail="Encoding failed: empty vectors")

        # [P0 架构增强] 混合池化策略 (Hybrid Pooling)
        # 1. Mean Pooling: 代表文档整体主题，适合“主题聚类”
        # 2. Max Pooling: 提取分段中最显著的特征（得分最高的局部），适合“长文搜核心事实”
        mean_vector = np.mean(all_vectors, axis=0).tolist()
        max_vector  = np.max(all_vectors, axis=0).tolist()
        
        cost_ms = int((time.time() - start_time) * 1000)
        
        print(f"📄 [LongDocVector] text_len={len(text)} | segments={len(segments)} | cost={cost_ms}ms")
        
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "vector": mean_vector,      # 默认保持 Mean，确保下游兼容
                "max_vector": max_vector,   # 新增 Max 特征供高阶相似度比对
                "segments": len(segments),
                "costMs": cost_ms
            }
        }
    except HTTPException:
        raise
    except Exception as e:
        print(f"❌ [LongDocVector] Error: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))



# ─────────────────────────────────────────────────────────────────────────────
# HyDE 假设文档缓存（独立缓存，避免与 intent_cache 互污）
_hyde_cache: dict = {}

# LLM Reranker 结果缓存（query + docs MD5 → scores list，命中时跳过 Ollama 调用）
_llm_rerank_cache: dict = {}


@app.post("/api/ai/vector/hyde")
def encode_hyde(req: QueryRequest):
    """
    业务功能：HyDE（Hypothetical Document Embedding）向量化接口
    核心原理：用 LLM 将短 query 扩展为「假设文档」，再用 BGE-M3 对假设文档编码。
    假设文档与真实入库文档词汇/语义更接近，向量余弦相似度从 ~0.21 提升至 0.65+。
    关键流程：query → LLM 生成假设文档 → BGE-M3 向量化（无指令前缀）→ 返回向量
    降级路径：LLM 失败时直接对原始 query 编码，确保接口不中断服务。
    """
    import requests as req_lib
    query = req.text.strip()
    if not query:
        raise HTTPException(status_code=400, detail="Query text cannot be empty")

    start_time = time.time()

    # 命中 HyDE 缓存直接返回，避免重复 LLM 调用
    if query in _hyde_cache:
        print(f"🎯 [HyDE Cache] '{query[:20]}' hit, cost 0ms")
        return {"code": 200, "msg": "success", "data": {"vector": _hyde_cache[query], "costMs": 0, "source": "cache"}}

    # Step 1：用 LLM 将短 query 扩展为假设文档
    # [HyDE Prompt 升级] 三段式 Prompt：
    #   ① System 角色：政务法律检索专家，增强领域理解
    #   ② 术语映射表：帮助 LLM 将文学/口语词汇映射为法律规范用词
    #     （如「缄默」→「保密义务」，「居中」→「调解员/中立第三方」）
    #   ③ 输出约束：直接输出摘要，避免 0.5b 模型生成无关解释
    # HyDE 专用模型：使用 7B 大模型，有足够法律/政务知识生成精准假设文档
    # 如需修改，设置环境变量 HYDE_LLM_MODEL
    system_prompt = (
        "你是一位政务/公安/法律文件检索专家。"
        "用户的输入是口语化的问题或需求，你的任务是生成一段正式政务政策/法律/公文文件摘要，该摘要应能直接对应并回答用户的这个问题。"
        "关键原则：1)先判断问题所属领域（医疗/法律/行政/安全/公安等）；2)在该领域内生成规范条文文字；3)直接输出60-80字的正式条文文本，不要解释。"
    )
    # 构建多样化 few-shot 示例引导 LLM 从问题领域内检索正确条文
    hyde_prompt = (
        f"示例1：问题：开办这个调解机构需要多少錢？ → 摘要：设立商事调解组织应当符合下列条件：有30万元以上的资产。\n"
        f"示例2：问题：大医院能不能把看病号源留一些给社区卫生院？ → 摘要：三级医院应按规定比例预留特定数量普通门诊号源，优先满足社区卫生服务中心转识患者需求。\n"
        f"现在请对以下查询生成一段直接对应的正式条文摘要，需要属于与此问题对应领域的政务文件，不得庄尌或跨领域。13-80字，不要解释起因。\n"
        f"查询：{query}\n摘要："
    )
    
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": hyde_prompt}
    ]
    
    content = LLMClient.ask(
        messages=messages, 
        model_env_key="HYDE_LLM_MODEL", 
        temperature=0.2, 
        max_tokens=120, 
        timeout=30.0
    )
    
    hypothetical_doc = query  # 降级默认值：LLM 失败时退回原始 query
    if content and len(content) > 10:
        hypothetical_doc = content
        print(f"💡 [HyDE] '{query[:20]}' → 假设文档({len(hypothetical_doc)}字): '{hypothetical_doc[:60]}...'")
    elif not content:
        print(f"⚠️ [HyDE] LLM 扩展失败，退回原始 query 编码")

    # Step 2：对假设文档编码（不加指令前缀，与入库文档在同一向量空间）
    try:
        vectors = model_manager.encode(hypothetical_doc)
        cost_ms = int((time.time() - start_time) * 1000)
        if not vectors:
            raise HTTPException(status_code=500, detail="HyDE embedding failed")
        vec = vectors[0]
        if len(_hyde_cache) > 2000:
            _hyde_cache.clear()
        _hyde_cache[query] = vec
        print(f"🔮 [HyDE] '{query[:20]}' | Cost: {cost_ms}ms")
        return {"code": 200, "msg": "success", "data": {"vector": vec, "costMs": cost_ms, "source": "hyde"}}
    except HTTPException:
        raise
    except Exception as e:
        print(f"❌ [HyDE] Error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ─────────────────────────────────────────────────────────────────────────────
# ColBERT MaxSim 评分端点（替代 BGE-Reranker Cross-Encoder）
# ─────────────────────────────────────────────────────────────────────────────

class ColbertRequest(BaseModel):
    query: str
    documents: List[str]

@app.post("/api/ai/colbert/score")
def colbert_score(req: ColbertRequest):
    """
    业务功能：ColBERT Late Interaction MaxSim 评分接口
    核心原理：BGE-M3 已输出 last_hidden_state（token 级向量），
              对每个 query token 找到与 document tokens 最相似的向量（MaxSim），
              累加后得到文档最终分。比 Cross-Encoder 更擅长跨文体语义（「缄默」≈「保密」）。
    关键流程：query + docs → encode_colbert（全量 token 向量）
              → Q_vecs @ D_vecs.T → max(axis=1) → mean → 相关性分数
    分数范围：[0, 1]（L2 归一化余弦相似度的 MaxSim 均值），无需 sigmoid
    降级路径：encode_colbert 失败时自动退回 BGE-Reranker，保证接口不中断。
    """
    if not req.query or not req.documents:
        raise HTTPException(status_code=400, detail="query and documents are required")
    start_time = time.time()

    # --- ColBERT 主路径 ---
    all_texts = [req.query] + req.documents
    colbert_vecs = model_manager.encode_colbert(all_texts)

    if colbert_vecs and colbert_vecs[0]:
        q_vecs = np.array(colbert_vecs[0], dtype=np.float32)  # [q_len, 1024]
        scores = []
        for d_vecs_list in colbert_vecs[1:]:
            if not d_vecs_list:
                scores.append(0.0)
                continue
            d_vecs = np.array(d_vecs_list, dtype=np.float32)  # [d_len, 1024]
            # MaxSim：每个 query token 与所有文档 token 的最大余弦相似度
            sim_matrix = q_vecs @ d_vecs.T                     # [q_len, d_len]
            maxsim_per_q = sim_matrix.max(axis=1)              # [q_len]
            doc_score = float(maxsim_per_q.mean())             # 平均 MaxSim
            scores.append(doc_score)

        cost_ms = int((time.time() - start_time) * 1000)
        print(f"🎯 [ColBERT] Query: '{req.query[:20]}' | Docs: {len(req.documents)} | Cost: {cost_ms}ms")
        for i, s in enumerate(scores):
            print(f"  - Doc {i+1}: MaxSim={s:.4f} | Preview: {req.documents[i][:50]}")
        return {"code": 200, "msg": "success", "data": {"scores": scores, "costMs": cost_ms, "mode": "colbert"}}

    # --- 降级：ColBERT 失败时退回 BGE-Reranker ---
    print("⚠️ [ColBERT] encode_colbert returned empty, fallback to BGE-Reranker")
    scores = model_manager.rerank(req.query, req.documents)
    cost_ms = int((time.time() - start_time) * 1000)
    return {"code": 200, "msg": "success", "data": {"scores": scores, "costMs": cost_ms, "mode": "reranker_fallback"}}

# ─────────────────────────────────────────────────────────────────────────────
# LLM Reranker（qwen2.5:7b 批量打分，解决向量模型无法理解知识映射的根本问题）
# 核心原理：7B 大模型有足够的法律/政务领域常识，能理解"最少需要多少钱"→"需要30万元资产"
#           的语义关联，而 BGE-M3 向量距离无法识别此类知识推理关系
# ─────────────────────────────────────────────────────────────────────────────

class LlmRerankRequest(BaseModel):
    query: str
    documents: List[str]

@app.post("/api/ai/llm/rerank")
def llm_rerank(req: LlmRerankRequest):
    """
    业务功能：LLM 大模型语义重排接口
    核心原理：使用 qwen2.5:7b 对候选文档与查询的相关性进行批量评分（0-10分）。
              与 ColBERT MaxSim 的本质区别：
              - ColBERT = token 级向量的最大相似度（字面/结构相似性）
              - LLM Reranker = 语言理解 + 领域推理（"需要多少钱" = "资产要求"）
    关键流程：query + top-N docs → 批量 Prompt → 7B LLM 输出 N 个评分 → 归一化
    分数范围：[0, 10]（直接输出整数），归一化到 [0.0, 1.0] 后返回
    性能控制：一次调用处理所有候选，约 2-5s（比逐个调用快 N 倍）
    降级路径：LLM 调用失败时返回 None，Java 端降级到 ColBERT
    """
    if not req.query or not req.documents:
        raise HTTPException(status_code=400, detail="query and documents are required")

    # [LLM缓存] 相同 query + 前3篇文档内容 MD5 为 key，命中时直接返回历史评分，完全跳过 Ollama调用（5-20s）
    import hashlib
    cache_key = req.query + "_" + hashlib.md5("".join(req.documents[:3]).encode("utf-8")).hexdigest()
    if cache_key in _llm_rerank_cache:
        cached_scores = _llm_rerank_cache[cache_key]
        print(f"🎯 [LLM Cache] Query: '{req.query[:20]}' hit, cost 0ms, {len(cached_scores)} scores")
        return {"code": 200, "msg": "success", "data": {"scores": cached_scores, "costMs": 0, "model": "cache"}}

    llm_model = os.getenv("RERANKER_LLM_MODEL", "qwen2.5:7b")
    start_time = time.time()

    # 批量构建 Prompt：一次调用处理所有候选文档
    docs_text = "\n".join(
        f"文档{i+1}：{doc[:200]}"  # 限制每个文档最多 200 字，控制 token 数
        for i, doc in enumerate(req.documents)
    )
    prompt = (
        f"查询：{req.query}\n\n"
        f"请对以下每个文档与查询的相关性打分（0-10分，10分最相关，0分完全无关）：\n\n"
        f"{docs_text}\n\n"
        f"输出要求：只输出 {len(req.documents)} 个数字，每行一个，顺序对应文档1到文档{len(req.documents)}，不要任何解释。\n"
        f"评分："
    )

    messages = [
        {"role": "system", "content": "你是文档相关性评判专家。严格按格式输出，每行一个0-10的整数。"},
        {"role": "user", "content": prompt}
    ]

    try:
        content = LLMClient.ask(
            messages=messages,
            model_env_key="RERANKER_LLM_MODEL",
            temperature=0.0,
            max_tokens=1500,
            timeout=45.0
        )

        if content:
            # 解析输出：提取所有数字
            numbers = re.findall(r'\d+(?:\.\d+)?', content)
            
            # 若连最基本的数字都没提取到，直接退还 ColBERT 避免强行打 0 分冤假错案
            if not numbers:
                 print(f"⚠️ [LLM Reranker] LLM produced no numbers. Fallback to ColBERT.")
                 return {"code": 200, "msg": "fallback", "data": {"scores": None, "costMs": 0, "model": "fallback"}}
                 
            scores_raw = [float(n) for n in numbers[:len(req.documents)]]

            # 补全缺失的分数（LLM 可能输出少于预期的数字）
            while len(scores_raw) < len(req.documents):
                scores_raw.append(0.0)

            # 归一化到 [0, 1]
            scores = [min(s / 10.0, 1.0) for s in scores_raw]
            cost_ms = int((time.time() - start_time) * 1000)

            print(f"🧠 [LLM Reranker] Query: '{req.query[:20]}' | Docs: {len(req.documents)} | Cost: {cost_ms}ms")
            for i, (s_raw, s) in enumerate(zip(scores_raw, scores)):
                print(f"  - Doc{i+1}: {s_raw:.0f}/10 ({s:.3f}) | {req.documents[i][:40]}")

            # 写入缓存：超 500 条时清空防止内存泄漏
            if len(_llm_rerank_cache) > 500:
                _llm_rerank_cache.clear()
            _llm_rerank_cache[cache_key] = scores

            return {"code": 200, "msg": "success", "data": {"scores": scores, "costMs": cost_ms, "model": llm_model}}
        else:
            print(f"⚠️ [LLM Reranker] 调用返回空或者失败")

    except Exception as e:
        print(f"⚠️ [LLM Reranker] 调用失败，返回 None（Java 端降级到 ColBERT）: {e}")

    # 降级：返回 None 列表，Java 端检测到 null 时降级到 ColBERT
    return {"code": 200, "msg": "fallback", "data": {"scores": None, "costMs": 0, "model": "fallback"}}

@app.post("/api/ai/rerank")
def rerank_documents(req: RerankRequest):
    """
    业务功能：对初筛结果进行 Cross-Encoder 深度重排 (带结果缓存优化)
    """
    if not req.query or not req.documents:
        raise HTTPException(status_code=400, detail="Query and documents are required")
        
    start_time = time.time()
    try:
        scores = model_manager.rerank(req.query, req.documents)
        cost_ms = int((time.time() - start_time) * 1000)
        
        # 调试输出：打印文档片段与对应得分
        print(f"📊 [Rerank Detail] Query: '{req.query}' | Docs: {len(req.documents)}")
        if scores:
            for i, score in enumerate(scores):
                doc_snippet = req.documents[i][:50].replace('\n', ' ')
                print(f"  - Doc {i+1}: {doc_snippet}... | Score: {score:.4f}")
        
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "scores": scores,
                "costMs": cost_ms
            }
        }
    except Exception as e:
        print(f"❌ [Rerank] Error: {str(e)}")
        return {
            "code": 501,
            "msg": f"Reranking skipped: {str(e)}",
            "data": None
        }

@app.post("/api/ai/rerank/document-similarity")
def rerank_document_similarity(req: DocumentSimilarityRerankRequest):
    """
    Document-level similarity rerank for editor recommendations.

    Dense vector retrieval is only the recall stage; this endpoint uses the
    cross-encoder reranker to judge query-document relevance before Java shows
    recommendations to users.
    """
    if not req.query or not req.documents:
        raise HTTPException(status_code=400, detail="Query and documents are required")

    start_time = time.time()
    try:
        scores = model_manager.rerank(req.query, req.documents)
        cost_ms = int((time.time() - start_time) * 1000)
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "scores": scores,
                "costMs": cost_ms,
                "mode": "cross_encoder"
            }
        }
    except Exception as e:
        print(f"❌ [DocumentSimilarityRerank] Error: {str(e)}")
        return {
            "code": 501,
            "msg": f"Document similarity rerank skipped: {str(e)}",
            "data": None
        }

@app.post("/api/ai/rerank/sentences")
def rerank_sentences(req: SentenceScoresRequest):
    """
    业务功能：对搜索结果中的句子进行批量打分，用于选择最佳高亮片段
    """
    if not req.query or not req.sentences:
        return {"code": 400, "msg": "Missing query or sentences"}
    
    start_time = time.time()
    try:
        scores = model_manager.rerank(req.query, req.sentences)
        cost_ms = int((time.time() - start_time) * 1000)
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "scores": scores,
                "costMs": cost_ms
            }
        }
    except Exception as e:
        return {"code": 500, "msg": str(e)}

@app.get("/api/ai/health")
def health_check():
    """
    业务功能：AI 服务健康检查接口，向 Java 侧暴露当前的推理设备类型（CPU/DML）。
    Java SearchService 根据 acceleration 字段决定 rerankLimit 的阈值：
    - CPU 模式：rerankLimit=6，防止超时
    - 其他模式：rerankLimit=50（GPU 充沛环境）
    """
    status = model_manager.get_health_status()
    return {
        "status": "ok",
        "model_loaded": status.get("model_loaded", False),
        "reranker_loaded": status.get("reranker_loaded", False),
        "acceleration": status.get("acceleration", "CPU"),
        "device": status.get("device", "CPU"),
        "providers": status.get("providers", [])
    }

@app.post("/api/ai/config/reload")
def reload_config(cfg: dict):
    try:
        model_manager.update_config(cfg)
        return {"code": 200, "msg": "Config applied successfully"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to apply config: {str(e)}")

class IntentRewriteRequest(BaseModel):
    query: str
    domain: Optional[str] = None # 预留支持特定领域的Prompt路由

intent_cache = {}

@app.post("/api/ai/intent/rewrite")
def rewrite_intent(req: IntentRewriteRequest):
    """
    业务功能：基于本地大模型执行动态意图重写（Few-Shot 提取核心名词）
    """
    import requests
    query = req.query.strip()
    if not query or len(query) < 4:
        return {"code": 200, "data": query}
        
    # 命中缓存，保证 O(1) 极速响应
    if query in intent_cache:
        print(f"🎯 [Intent Cache Match] '{query}' -> '{intent_cache[query]}'")
        return {"code": 200, "data": intent_cache[query]}
        
    # [方案X2] Intent Rewrite Prompt 升级：在关键词提取基础上新增政务缩略语展开指令
    # 升级原则：加入缩略语->全称 Few-Shot 规则（如「环评」->「环境影响评价」）
    # 根因：原 Prompt 仅提取核心词，遇政务缩略语时输出缩写，导致 BM25 无法命中文档全称
    prompt = f"""从下面的提问中提取2~5个核心名词（政务/法律/医疗专业词），同时将政务缩略语展开为全称，空格分隔，勿解释。
规则：若词语是政务缩略语，请输出全称（如『环评』->『环境影响评价』，『三资』->『外资企业』，『政采』->『政府采购』，『城改』->『城市改造』，『食药监』->『食品药品监督管理局』）。
提问：{query}
核心词："""

    try:
        messages = [
            {"role": "system", "content": "You are a helpful text classification assistant."},
            {"role": "user", "content": prompt}
        ]
        
        content = LLMClient.ask(
            messages=messages,
            model_env_key="LLM_MODEL",
            temperature=0.1,
            max_tokens=15,
            timeout=8.0
        )
        
        if content:
            # 清洗：剥离任意 LLM 输出的中文标签前缀（"解析："、"政务名词："等），只取第一行
            import re
            # 若输出含冒号前缀（如 "解析：xxx"），取冒号之后的内容
            colon_split = re.split(r'[:：]', content, maxsplit=1)
            rewritten = colon_split[-1].strip() if len(colon_split) > 1 else content
            # 只保留第一行，去除引号、顿号
            rewritten = rewritten.split('\n')[0].replace('"', '').replace("'", '').replace('，', ' ').replace('、', ' ').strip()
            print(f"🧠 [LLM Rewrite] '{query}' -> '{rewritten}'")
            
            if len(intent_cache) > 5000:
                intent_cache.clear()
            intent_cache[query] = rewritten
            
            return {"code": 200, "data": rewritten}
        else:
            print(f"⚠️ [LLM Rewrite] 调用返回空或者失败")

    except Exception as e:
        print(f"❌ [LLM Router Exception] {str(e)}")
        
    return {"code": 200, "data": query}

# ─────────────────────────────────────────────────────────────────────────────
# 合并接口：单次 LLM 同时返回「意图改写词」+「HyDE 假设文档向量」
# 核心价值：将原本两次独立 Ollama 调用（Rewrite + HyDE）合并为一次，
#           消除 Ollama 串行排队等待（原逻辑虽用 Java CompletableFuture "并行"，
#           但 Ollama 内部串行执行，实际总耗时 = Rewrite(5~8s) + HyDE(5~15s)）。
# 优化后：单次 LLM 推理 ~8~15s，节省一整个排队轮次。
# ─────────────────────────────────────────────────────────────────────────────

class RewriteAndHydeRequest(BaseModel):
    query: str
    need_vector: bool = True       # 是否需要 HyDE 向量（长查询/禁用向量时可跳过）
    query_type: Optional[str] = None  # 文档子类型（"公示"/"法规"/"通知"），非空时走专属 Prompt

# 合并接口独立缓存（分开管理，互不干扰）
_rewrite_hyde_cache: dict = {}

@app.post("/api/ai/intent/rewrite_and_hyde")
def rewrite_and_hyde(req: RewriteAndHydeRequest):
    """
    业务功能：单次 LLM 调用同时完成「意图改写」和「HyDE 假设文档」生成，再对假设文档编码。
    关键流程：
      1. 检查合并缓存（query 完全命中时 0ms 返回）
      2. 构造 dual-output Prompt，单次调用 LLM 输出两段内容：
         - 核心词（用于 BM25 改写）
         - 假设文档（用于 HyDE 向量）
      3. 对假设文档调用 BGE-M3 编码（~300ms）
      4. 返回 rewritten_query + vector
    降级路径：LLM 失败时，rewritten_query 退回原始 query，vector 对原始 query 编码。
    """
    query = req.query.strip()
    if not query:
        return {"code": 200, "data": {"rewritten_query": query, "vector": None}}

    # 命中合并缓存，直接返回（含 DocType 专属 key 避免与通用缓存互串）
    cache_key = query + ("_v" if req.need_vector else "_nv") + (f"_{req.query_type}" if req.query_type else "")
    if cache_key in _rewrite_hyde_cache:
        cached = _rewrite_hyde_cache[cache_key]
        print(f"🎯 [RewriteHyDE Cache] '{query[:20]}' hit, cost 0ms")
        return {"code": 200, "data": cached}

    # ─────────────────────────────────────────────────────────────────────
    # [HyDE增强] DocType 专属路径：文档类型型查询（Java 侧 DOC_TYPE_PATTERN 命中后传入）
    # 根因：直接编码 "2024年任职公示" 向量≈[文档类型]语义域，而人员条目在[人事数据]语义域。
    # 修复：根据 query_type 切换专属 HyDE Prompt，LLM 生成对应格式的假设文档，
    #       使 BGE-M3 向量坐落于目标内容的语义域，KNN 就能召回正确的人员/条文 chunk。
    # ─────────────────────────────────────────────────────────────────────
    if req.query_type in ("公示", "法规", "通知"):
        # 根据文档子类型选择专属 System Prompt + User Prompt
        if req.query_type == "公示":
            # 公示/任职型：生成人员名单格式（与被检索 chunk 语义空间对齐）
            doc_type_system = (
                "你是人事/干部管理专家。请根据用户的查询，生成一段标准任职公示中的人员名单文本，"
                "包含 2-3 位拟任人员的基本信息（姓名、性别、出生年、籍贯/民族、现任职位、拟任职位）。"
                "直接输出人员列表内容，不含标题、前言或解释。"
            )
            doc_type_user = (
                f"查询：{query}\n"
                "请生成符合该公示主题的人员名单片段，格式如下：\n"
                "1. 张X，男，1985年生，汉族，现任XX镇党委副书记，拟任XX镇党委书记、镇长。\n"
                "2. 李X，女，1988年生，回族，现任XX县教育局副局长，拟任XX县教育局局长。\n"
                "请仿照上述格式生成，姓名用模糊代替，职位与查询主题相关："
            )
        elif req.query_type == "法规":
            # 法规/条例型：生成法律条文样式
            doc_type_system = (
                "你是政务/法律专家。请根据用户的查询，生成一段法律法规或规范性文件的条文原文，"
                "包含具体的规定内容、适用范围和法律依据，格式正式规范。"
                "直接输出条文内容，不含解释。"
            )
            doc_type_user = (
                f"查询：{query}\n"
                "请生成该类法规文件中典型的条文片段（60-100字）："
            )
        else:
            # 通知/意见/方案型：生成政务通知正文
            doc_type_system = (
                "你是政务工作人员。请根据用户的查询，生成一段政务通知或工作方案的正文内容，"
                "包含具体工作要求、时间节点或执行措施。直接输出正文内容。"
            )
            doc_type_user = (
                f"查询：{query}\n"
                "请生成该类通知的正文片段（60-100字）："
            )

        hypothetical_doc = query  # LLM 失败时退回原始 query
        try:
            messages = [
                {"role": "system", "content": doc_type_system},
                {"role": "user",   "content": doc_type_user}
            ]
            raw = LLMClient.ask(
                messages=messages,
                model_env_key="HYDE_LLM_MODEL",
                temperature=0.2,
                max_tokens=150,
                timeout=25.0
            )
            if raw and len(raw) >= 10:
                hypothetical_doc = raw
                print(f"💡 [HyDE DocType={req.query_type}] '{query[:20]}' → 假设文档({len(raw)}字): '{raw[:50]}...'")
        except Exception as e:
            print(f"⚠️ [HyDE DocType] LLM 调用失败，退回原始 query 编码: {e}")

        # BGE-M3 对假设文档编码（不加指令前缀，与入库文档同一向量空间）
        vector = None
        if req.need_vector:
            try:
                vecs = model_manager.encode(hypothetical_doc)
                vector = vecs[0] if vecs else None
            except Exception as e:
                print(f"⚠️ [HyDE DocType] BGE-M3 encode error: {e}")

        result = {"rewritten_query": query, "vector": vector}
        if len(_rewrite_hyde_cache) > 2000:
            _rewrite_hyde_cache.clear()
        _rewrite_hyde_cache[cache_key] = result
        print(f"✅ [HyDE DocType={req.query_type}] vector={'生成' if vector else '失败/降级'}, '{hypothetical_doc[:40]}'")
        return {"code": 200, "data": result}
    # ─────────────────────────────────────────────────────────────────────

    # 极短查询（<4字）或 不带典型疑问词的短语（<=15字，如标语“铺就象牙塔骄子职场之路”）
    # 应当跳过 LLM HyDE 扩写，避免核心词被“大白话”稀释导致向量查不到原句。
    # 分类判断：是否为「明确疑问意图」的查询
    # [修复] 去掉"条件""规定"等专业名词，避免"设立调解机构的条件"被误认为疑问句而触发 HyDE
    # 保留真正的问句助词（怎么/如何/为什么/多少/是否）
    question_words = ["怎么", "怎样", "如何", "什么", "为何", "为啥",
                      "多少", "几个", "吗", "呢", "能否", "可否", "是否", "能不能"]
    is_question = any(qw in query for qw in question_words)

    # [Fix] HyDE 触发规则收紧：
    # - ≤12字：无论是否含疑问词，均跳过 LLM（"修复了什么内容"8字含"什么"→原来会触发LLM+2次BGE=10s > Java 5s timeout）
    # - 12-20字 且无疑问词：视为专业关键词，跳过 LLM（避免幻觉污染）
    # - 12-20字 且含疑问词：触发 HyDE（词汇量足，LLM 有价值，如"调解机构要多少钱"）
    # - >20字：无条件触发 HyDE（信息量充足，LLM 扩写价值最大）
    is_short_keyword = len(query) <= 20 and (not is_question or len(query) <= 12)

    if len(query) < 4 or is_short_keyword:
        vector = None
        if req.need_vector:
            try:
                vecs = model_manager.encode(query)
                vector = vecs[0] if vecs else None
            except Exception as e:
                print(f"⚠️ [RewriteHyDE] Fallback encode error: {e}")
        
        result = {"rewritten_query": query, "vector": vector}
        if len(_rewrite_hyde_cache) > 2000:
            _rewrite_hyde_cache.clear()
        _rewrite_hyde_cache[cache_key] = result
        if len(query) < 4:
            reason = "极短查询"
        elif len(query) <= 12:
            reason = "短查询(≤12字，防LLM超时)"
        else:
            reason = "专业关键词(≤20字无疑问词)"
        print(f"🛡️ [RewriteHyDE] '{query}' 命中防泛化规则({reason})，直接编码跳过大模型扩写。")
        return {"code": 200, "data": result}

    llm_url   = os.getenv("LLM_API_URL",  "http://127.0.0.1:11434/v1/chat/completions")
    llm_model = os.getenv("LLM_MODEL",    "qwen2.5:7b")

    # [性能优化 P0] 「BGE 立即返回 + Qwen 后台异步预热缓存」策略
    # 根因：Qwen 7B GPU 推理需 3~8s，在主线程同步等待导致每次长查询耗时 4~5s。
    # 策略：
    #   ① 立即用 BGE-M3 编码原始 query 作为本次请求的 fallback 向量（~300ms，无需等 LLM）
    #   ② 启动后台 daemon 线程异步运行 Qwen，生成假设文档后写入 _rewrite_hyde_cache
    #   ③ 下次相同 query 请求命中缓存，0ms 获得 HyDE 优质向量，质量不损失
    # 效果：首次请求 ~300ms，重复请求 ~0ms（缓存）；不再有 4s 阻塞
    vector = None
    if req.need_vector:
        try:
            t0 = time.time()
            vecs = model_manager.encode(query)
            vector = vecs[0] if vecs else None
            print(f"⚡ [DirectEncode] BGE-M3 encoded query in {int((time.time()-t0)*1000)}ms")
        except Exception as e:
            print(f"⚠️ [DirectEncode] encode failed: {e}")

    # 构建 HyDE Prompt（后台线程用）
    hyde_system_prompt = (
        "你是政务/公安/法律领域专家。请生成一段100字以内的简短文档片段，"
        "该片段是能直接回答用户查询的文档正文内容。"
        "只输出文档内容本身，不含解释、前缀、标签。"
    )
    hyde_user_prompt = (
        f"查询：{query}\n"
        "请生成一段法规文档原文片段，直接包含该查询所寻找的答案内容："
    )

    # 立即写入 BGE fallback 结果（供本次请求使用）
    fallback_result = {"rewritten_query": query, "vector": vector}
    if len(_rewrite_hyde_cache) > 2000:
        _rewrite_hyde_cache.clear()
    # 先写 fallback，确保并发请求命中时也能立即返回（后台线程完成后会覆盖为 HyDE 版本）
    _rewrite_hyde_cache[cache_key] = fallback_result

    def _async_hyde_warm():
        """
        后台异步运行 Qwen 生成假设文档，完成后覆盖写入缓存。
        下次相同查询命中缓存时直接返回 HyDE 质量的向量，质量无损失。
        """
        try:
            messages = [
                {"role": "system", "content": hyde_system_prompt},
                {"role": "user",   "content": hyde_user_prompt}
            ]
            raw = LLMClient.ask(
                messages=messages,
                model_env_key="LLM_MODEL",
                temperature=0.3,
                max_tokens=150,
                timeout=30.0
            )

            if raw and len(raw) >= 10:
                hyde_vector = None
                if req.need_vector:
                    try:
                        hyde_vecs = model_manager.encode(raw)
                        hyde_vector = hyde_vecs[0] if hyde_vecs else None
                    except Exception:
                        pass
                # 覆盖缓存为 HyDE 优化版本
                _rewrite_hyde_cache[cache_key] = {"rewritten_query": raw, "vector": hyde_vector}
                print(f"✅ [HyDE Async] '{query[:20]}' 缓存预热完成，假设文档({len(raw)}字): '{raw[:40]}...'")
            else:
                print(f"⚠️ [HyDE Async] LLM 输出过短，保留 BGE fallback 缓存")
        except Exception as e:
            print(f"⚠️ [HyDE Async] 后台预热失败（不影响当前请求）: {e}")

    import threading
    threading.Thread(target=_async_hyde_warm, daemon=True, name=f"hyde-warm-{query[:8]}").start()

    # 回写 intent_cache（BM25 改写词仍用原始 query，HyDE 完成后由异步线程覆盖）
    if len(intent_cache) > 5000:
        intent_cache.clear()
    intent_cache[query] = query  # fallback，异步线程不修改此项

    return {"code": 200, "data": fallback_result}


@app.post("/api/ai/qa/generate")
def generate_qa_questions(req: QueryRequest):
    """
    QA 问题生成 HTTP 门面（Facade）。

    业务功能：为指定的政务/法律条文内容，生成用户可能提问的口语化问题列表。

    架构说明（重构）：
      原实现将 Prompt 定义、LLM 调用、响应解析完整逻辑写在本函数中，
      导致 rag_pipeline.py 需要通过 HTTP 自环调用才能复用同一段逻辑（违反 DIP）。
      重构后：本 endpoint 作为纯 HTTP 门面，所有生成逻辑委托给 QAGenerator。
      调用链：HTTP Client → /api/ai/qa/generate → QAGenerator.generate()
      直接调用：rag_pipeline._generate_and_index_qa_pairs → QAGenerator.generate_batch()

    降级路径：LLM 失败时 QAGenerator 内部返回空列表，此处直接透传。
    """
    from core.qa_generator import qa_generator
    questions = qa_generator.generate(req.text)
    return {"code": 200, "data": questions}




# ─────────────────────────────────────────────────────────────────────────────
# QA 检索接口：用 dense 向量在 ES QA 索引中 KNN 召回高置信度问答对
# 供 Java QaInjectionStep 调用，将高相关 QA 注入候选池 HEAD
# ─────────────────────────────────────────────────────────────────────────────

class QaSearchRequest(BaseModel):
    vector: List[float]          # dense query 向量（1024 维，由 Java 侧预取）
    query_text: str              # 原始查询词（用于 bigram 重叠校验）
    force_source: Optional[str] = None  # 租户数据源过滤（对应 metadata.source）
    top_k: int = 3               # 最多返回几条 QA
    # [权限对齐] Java 侧 UserContextHolder.getAclTokens() 预计算的 token 集合
    # 与主索引 buildLegacyPermFilter 对称：检索前注入，直接用于 ES terms filter
    # 兜底：空列表时降级为「无新架构 token」→ must_not exists 旧架构路径（历史数据可见）
    acl_tokens: Optional[List[str]] = None

@app.post("/api/ai/qa/search")
def qa_search(req: QaSearchRequest):
    """
    业务功能：在 Elasticsearch QA 索引（kb_qa_*）中按向量相似度召回高置信度问答对。
    关键流程：
      1. 接收 Java 侧预取的 dense query 向量（1024 维 BGE-M3）
      2. 对 QA 索引执行 KNN 检索（knn.field=dense_vector，num_candidates=50）
      3. 返回 top_k 条 QA 候选（含 _rrf_score 用于 Domain Filter 判断）
    设计原则：
      - 不在 Python 端做 Domain Filter（由 Java QaInjectionStep 执行，保持职责单一）
      - force_source 仅用于 ES Filter（租户隔离），不影响向量相似度排序
    与 qa/generate 的区别：generate → 生成 QA 对（入库时用），search → 检索 QA（搜索时用）
    """
    if not req.vector:
        raise HTTPException(status_code=400, detail="vector is required")

    start_time = time.time()

    try:
        import requests as _req
        es_host = os.getenv("ES_HOST", "http://127.0.0.1:9200")
        # [轨道A] 改用 kb_qa_read 读别名，而非通配符 kb_qa_*
        # 好处：Reindex 期间别名可同时指向新旧两个物理索引，查询自动合并结果；
        #       正常状态下别名精确指向单一物理索引，消除通配符扫描开销
        from core.indexing.es_setup import QA_INDEX_READ_ALIAS
        qa_index = QA_INDEX_READ_ALIAS
        # [P1-6 对齐] 读取与 rag_pipeline.py 相同的 ES 认证环境变量：
        # 根因：裸 HTTP 请求未携带 Authorization 头，开启 xpack.security 的 ES 返回 401。
        # 修复方案：仅当 ES_USER 非空时注入 Basic Auth，对无认证 ES 完全无副作用。
        _es_user = os.getenv("ES_USER", "")
        _es_pass = os.getenv("ES_PASS", "")
        _es_auth = (_es_user, _es_pass) if _es_user else None

        # 构造 KNN 检索请求
        # [字段名修复] QA 索引（kb_qa_pairs）的向量字段名是 question_vector，不是 dense_vector。
        # 根因：mapping 在 rag_pipeline._ensure_qa_index_exists() 中定义，字段与此处不一致，
        #       ES 找不到 dense_vector 字段直接返回 400。_source 中 answer→answer_content 同理。
        knn_query = {
            "knn": {
                "field": "question_vector",   # 与 kb_qa_pairs mapping 对齐
                "query_vector": req.vector,
                "k": req.top_k,
                "num_candidates": 50
            },
            "size": req.top_k,
            "_source": ["question", "answer_content", "answer_chunk_id", "doc_hash", "doc_version", "is_latest", "section_path", "source"]
        }

        # ── 构建统一 ACL 权限过滤子句（与主索引 buildLegacyPermFilter 完全对称）──────
        # 超管旁路：token 列表含 _SUPER_ADMIN 时返回 match_all，放行全量文档（与 Java 侧行为一致）；
        # 分支A（新架构）：acl_tokens 字段与用户 token 集合 terms 求交，命中任一即有权；
        # 分支B（旧架构兜底）：文档不存在 acl_tokens 字段（历史数据）→ must_not exists 放行；
        # 两个分支 should + minimum_should_match=1，确保新旧文档均可被检索。
        def _build_acl_filter(tokens: list) -> dict:
            # 超管旁路：包含 _SUPER_ADMIN 时直接 match_all，跳过所有文档级权限校验
            if "_SUPER_ADMIN" in tokens:
                return {"match_all": {}}
            if tokens:
                return {
                    "bool": {
                        "should": [
                            # 分支A：新架构，token 交集校验
                            {"terms": {"acl_tokens": tokens}},
                            # 分支B：旧架构存量文档（无 acl_tokens 字段），直接放行
                            {"bool": {"must_not": {"exists": {"field": "acl_tokens"}}}}
                        ],
                        "minimum_should_match": 1
                    }
                }
            else:
                # acl_tokens 为空（未传权限信息）：保守策略，只放行无 acl_tokens 的历史文档
                # 避免因 Java 传参缺失而意外暴露全量数据
                return {"bool": {"must_not": {"exists": {"field": "acl_tokens"}}}}

        _acl_filter = _build_acl_filter(req.acl_tokens or [])

        _base_filter = [
            # is_latest 版本过滤
            {
                "bool": {
                    "should": [
                        {"term": {"is_latest": True}},
                        {"bool": {"must_not": {"exists": {"field": "is_latest"}}}}
                    ],
                    "minimum_should_match": 1
                }
            },
            # [权限对齐] 用户 ACL Token 过滤（新架构 terms 匹配 + 旧架构 exists 兜底）
            _acl_filter
        ]
        if req.force_source:
            knn_query["knn"]["filter"] = {
                "bool": {
                    "must": [
                        {"term": {"source": req.force_source}},
                        *_base_filter
                    ]
                }
            }
        else:
            knn_query["knn"]["filter"] = {"bool": {"must": _base_filter}}

        resp = _req.post(
            f"{es_host}/{qa_index}/_search",
            json=knn_query,
            auth=_es_auth,      # None 时 requests 自动跳过 Authorization 头
            timeout=10.0,
            headers={"Content-Type": "application/json"}
        )

        if resp.status_code != 200:
            print(f"  [QA Search] ES 响应异常: {resp.status_code}")
            return {"code": 200, "data": []}

        hits = resp.json().get("hits", {}).get("hits", [])
        results = []
        for hit in hits:
            source = hit.get("_source", {})
            score = hit.get("_score", 0.0)
            # 转换为 _rrf_score 量级（与主管道对齐：subSim * 0.015）
            rrf_score = round(score * 0.015, 6)
            
            # [Fix] 组装符合 Java 侧 Candidate 期望的字段结构
            # 1. QA 答案文本作为 content 供 Java 端高亮和摘要使用
            source["content"] = source.get("answer_content", "")
            # 2. 构造 metadata 结构供归档字段使用 (doc_id/file_name 等依赖)
            file_name = source.get("source", "未知文档")
            source["metadata"] = {
                "source":       file_name,
                "owner":        file_name,    # QA 索引无 owner 字段，默认用文件名兜底
                "chunk_id":     source.get("answer_chunk_id", ""),
                # [T3] 透传 doc_hash 供 Java 侧 Result Collapsing 精确折叠
                # 根因：QA _id 中的 hash 与文档 chunk _id 的 hash 在文档重新入库后可能不一致，
                #       显式透传 doc_hash（Python 写入时存储的 file_base_hash）作为折叠基准。
                "doc_hash":     source.get("doc_hash", ""),
                "section_path": source.get("section_path", "")
            }

            results.append({
                "_id":          hit.get("_id"),
                "_source":      source,
                "_score":       score,
                "_rrf_score":   rrf_score,
                # [T3] 暴露置信度（原始 knn 余弦相似度），供 QaInjectionStep 三档分流
                # semantic 模式：score 本身即余弦相似度 [0,1]；BM25 模式需归一化
                "_qa_confidence": round(min(score, 1.0), 4),
                "content":      source.get("content", "")
            })

        cost_ms = int((time.time() - start_time) * 1000)
        print(f"  [QA Search] top_k={req.top_k} hits={len(results)} | Cost: {cost_ms}ms")
        return {"code": 200, "data": results, "costMs": cost_ms}

    except Exception as e:
        print(f"  [QA Search] 检索异常（降级返回空）: {e}")
        return {"code": 200, "data": []}


# ─────────────────────────────────────────────────────────────────────────────
# [T7] QA BM25 检索接口：关键词模式降级召回（无 queryVector 时使用）
# 使用 multi_match 对 question^3 / answer_content^1 做全文检索
# 供 QaInjectionStep 在 keyword 模式下调用（原来是直接 return，完全跳过 QA）
# ─────────────────────────────────────────────────────────────────────────────

class Bm25QaSearchRequest(BaseModel):
    query_text: str              # 原始查询词
    force_source: Optional[str] = None  # 租户数据源过滤
    top_k: int = 5               # BM25 匹配候选数稍多（弥补精度不如 knn）
    # [权限对齐] 与 QaSearchRequest.acl_tokens 语义一致，由 Java 侧统一注入
    acl_tokens: Optional[List[str]] = None

@app.post("/api/ai/qa/search/bm25")
def qa_search_bm25(req: Bm25QaSearchRequest):
    """
    业务功能：关键词模式下 QA 检索降级方案。
    关键流程：
      1. 无 query_vector 场景（keyword 模式）时，改用 BM25 multi_match 检索 QA
      2. question 字段权重^3（Q2Q 语义优先），answer_content 权重^1（内容兜底）
      3. BM25 分归一化至 [0,1]（÷10 近似），对齐 knn 置信度量级
      4. 返回结构与 /api/ai/qa/search 完全一致（Java 侧无感知差异）
    设计原则：
      - 覆盖关键词模式的 QA 盲区，实现三模式全覆盖
      - BM25 分通过 minimum_should_match 60% 防止单字词噪音命中
    """
    if not req.query_text or not req.query_text.strip():
        return {"code": 200, "data": []}

    start_time = time.time()

    try:
        import requests as _req
        es_host = os.getenv("ES_HOST", "http://127.0.0.1:9200")
        qa_index = os.getenv("QA_INDEX_PATTERN", "kb_qa_*")
        _es_user = os.getenv("ES_USER", "")
        _es_pass = os.getenv("ES_PASS", "")
        _es_auth = (_es_user, _es_pass) if _es_user else None

        # ── 构建 ACL 权限过滤子句（复用 KNN 通道相同逻辑，确保两路一致）────────────
        # 超管旁路：_SUPER_ADMIN → match_all（与 KNN 通道、Java buildLegacyPermFilter 三方一致）
        # 就地定义 lambda 避免模块级依赖；与 KNN 通道的 _build_acl_filter 逻辑完全一致
        _acl_tokens = req.acl_tokens or []
        if "_SUPER_ADMIN" in _acl_tokens:
            # 超管旁路：match_all 放行全量，无需 terms 过滤
            _acl_clause = {"match_all": {}}
        elif _acl_tokens:
            _acl_clause = {
                "bool": {
                    "should": [
                        {"terms": {"acl_tokens": _acl_tokens}},
                        {"bool": {"must_not": {"exists": {"field": "acl_tokens"}}}}
                    ],
                    "minimum_should_match": 1
                }
            }
        else:
            # acl_tokens 未传：保守策略，只放行无权限字段的历史文档
            _acl_clause = {"bool": {"must_not": {"exists": {"field": "acl_tokens"}}}}

        # is_latest 过滤（与 KNN 通道一致）+ ACL 权限过滤 + 租户过滤
        _bm25_filters = [
            {
                "bool": {
                    "should": [
                        {"term": {"is_latest": True}},
                        {"bool": {"must_not": {"exists": {"field": "is_latest"}}}}
                    ],
                    "minimum_should_match": 1
                }
            },
            _acl_clause  # [权限对齐] 用户级 ACL Token 过滤
        ]
        if req.force_source:
            _bm25_filters.append({"term": {"source": req.force_source}})

        bm25_query = {
            "query": {
                "bool": {
                    "must": [
                        {
                            "multi_match": {
                                "query":                req.query_text,
                                # question 字段权重^3：Q2Q 语义优先（用户问题与知识库问题最直接对齐）
                                # answer_content 权重^1：内容兜底（用户描述的内容碰巧在答案中出现）
                                "fields":               ["question^3", "answer_content^1"],
                                "type":                 "best_fields",
                                "minimum_should_match": "60%"  # 防止单字词噪音
                            }
                        }
                    ],
                    "filter": _bm25_filters
                }
            },
            "size": req.top_k,
            "_source": ["question", "answer_content", "answer_chunk_id", "doc_hash", "doc_version", "is_latest", "section_path", "source"]
        }

        resp = _req.post(
            f"{es_host}/{qa_index}/_search",
            json=bm25_query,
            auth=_es_auth,
            timeout=3.0,
            headers={"Content-Type": "application/json"}
        )

        if resp.status_code != 200:
            print(f"  [QA BM25 Search] ES 响应异常: {resp.status_code}")
            return {"code": 200, "data": []}

        hits = resp.json().get("hits", {}).get("hits", [])
        results = []
        for hit in hits:
            source = hit.get("_source", {})
            bm25_score = hit.get("_score", 0.0)
            # BM25 分归一化：ES BM25 score ÷ 10 近似映射到 [0,1]（与 knn 余弦相似度量级对齐）
            # 注：BM25 分理论无上界，但实际 question 字段短文本匹配通常在 1~15 分之间
            normalized_conf = round(min(bm25_score / 10.0, 1.0), 4)
            rrf_score = round(normalized_conf * 0.015, 6)

            source["content"] = source.get("answer_content", "")
            file_name = source.get("source", "未知文档")
            source["metadata"] = {
                "source":       file_name,
                "owner":        file_name,
                "chunk_id":     source.get("answer_chunk_id", ""),
                "doc_hash":     source.get("doc_hash", ""),
                "section_path": source.get("section_path", "")
            }

            results.append({
                "_id":            hit.get("_id"),
                "_source":        source,
                "_score":         bm25_score,
                "_rrf_score":     rrf_score,
                "_qa_confidence": normalized_conf,  # 归一化置信度，供三档分流
                "content":        source.get("content", "")
            })

        cost_ms = int((time.time() - start_time) * 1000)
        print(f"  [QA BM25 Search] query='{req.query_text[:20]}' hits={len(results)} | Cost: {cost_ms}ms")
        return {"code": 200, "data": results, "costMs": cost_ms}

    except Exception as e:
        print(f"  [QA BM25 Search] 检索异常（降级返回空）: {e}")
        return {"code": 200, "data": []}


@app.post("/api/ai/colloquial/generate")
def generate_colloquial_phrases(req: QueryRequest):
    """
    业务功能：为政务/法律条文 chunk 生成「用户可能输入的口语化搜索短语」列表。
    核心原理：标语/口号类查询（如'铺就象牙塔骄子职场之路'）与文档原文向量空间距离远，
              但与文档口语化描述（如'帮大学生找工作的政策'）距离近。
              索引时为每个 chunk 生成并存储此类短语的聚合向量（colloquial_vector），
              检索时对标语/短句类查询使用 colloquial_vector 做 KNN，显著提升命中率。
    与 qa/generate 的区别：
      - qa/generate   → 生成「问题句」（如'需要多少钱？'），供意图类查询匹配
      - colloquial/generate → 生成「搜索短语」（如'机构资金门槛'），供标语/隐喻类查询匹配
    关键流程：fine chunk内容 → LLM 生成 2-3 个口语化搜索短语 → 返回短语列表
    降级路径：LLM 失败时返回空列表，由调用方决定是否跳过
    """
    import requests as req_lib
    import re as _re
    chunk = req.text.strip()
    if not chunk or len(chunk) < 8:
        return {"code": 200, "data": []}

    llm_url = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
    llm_model = os.getenv("LLM_MODEL", "qwen2.5:7b")

    system_prompt = (
        "你是一位政府文件检索专家，擅长理解普通用户会如何用口语、标语、隐喻来搜索政务/法律条文。"
        "你的任务是：根据条文内容，生成 2-3 个用户可能在搜索框输入的【搜索短语】（非问句）。"
        "输出要求：每行一个短语（3-15字），不加编号，不加标点，不加解释。"
        "风格：口语化、简短，可以是隐喻或标语风格，覆盖不同类型的表达方式。"
    )
    # few-shot 示例覆盖：标语型、口语型、隐喻型三种风格
    prompt = (
        f"# 示例\n"
        f"条文：各高等院校应加强就业指导和服务，提高毕业生就业率，重点帮扶困难群体就业。\n"
        f"口语化搜索短语（每行一个，不编号）：\n"
        f"帮大学生找工作的政策\n"
        f"铺就象牙塔骄子职场之路\n"
        f"大学毕业生就业扶持\n"
        f"\n"
        f"条文：设立商事调解组织须有30万元以上资产并保持独立性，不得以营利为目的。\n"
        f"口语化搜索短语（每行一个，不编号）：\n"
        f"调解机构成立条件\n"
        f"商事调解所设立要求\n"
        f"调解中心资金门槛\n"
        f"\n"
        f"# 当前任务\n"
        f"条文：{chunk}\n"
        f"口语化搜索短语（每行一个，不编号）："
    )

    try:
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user",   "content": prompt}
        ]
        
        content = LLMClient.ask(
            messages=messages,
            model_env_key="LLM_MODEL",
            temperature=0.4,
            max_tokens=80,
            timeout=45.0
        )
        
        if content:
            phrases = []
            import re as _re
            for line in content.split("\n"):
                p = _re.sub(r"^[\d１-９．。\-\.\*#\s]+", "", line).strip()
                # 过滤太短（<3字）或太长（>20字）的行
                if 3 <= len(p) <= 20 and p not in phrases:
                    phrases.append(p)
                if len(phrases) >= 3:
                    break
            print(f"  [Colloquial Gen] {len(phrases)} 个短语 | Chunk: '{chunk[:20]}...' | 短语: {phrases}")
            return {"code": 200, "data": phrases}
    except Exception as e:
        print(f"  [Colloquial Gen] LLM 失败: {e}")

    return {"code": 200, "data": []}


@app.post("/api/ai/similarity/compare")
def similarity_compare(req: SimilarityCompareRequest):
    """
    业务功能：计算两段文本在 BGE-M3 向量空间中的余弦相似度
    核心原理：对 text_a / text_b 分别调用 BGE-M3 Dense 编码，得到 1024 维归一化向量，
              再通过点积（等价于余弦）计算向量夹角的余弦值，范围 [-1, 1]。
    返回字段：
      - cosine: 余弦相似度分数
      - label:  语义标签（高度相似/语义相近/话题相关/语义疏远）
      - vec_a / vec_b: 原始向量（供前端未来做 2D PCA 投影备用）
    关键流程：text_a & text_b → encode() → np.dot / np.linalg.norm → cosine
    """
    if not req.text_a.strip() or not req.text_b.strip():
        raise HTTPException(status_code=400, detail="text_a and text_b cannot be empty")

    start_time = time.time()
    try:
        # 使用 BGE-M3 Dense 编码，不加查询指令前缀（两段文本对等比较）
        vecs_a = model_manager.encode(req.text_a)
        vecs_b = model_manager.encode(req.text_b)

        if not vecs_a or not vecs_b:
            raise HTTPException(status_code=500, detail="Embedding failed: empty result")

        vec_a = np.array(vecs_a[0], dtype=np.float32)
        vec_b = np.array(vecs_b[0], dtype=np.float32)

        # 计算余弦相似度（BGE-M3 输出已 L2 归一化，点积即余弦）
        norm_a = np.linalg.norm(vec_a)
        norm_b = np.linalg.norm(vec_b)
        if norm_a == 0 or norm_b == 0:
            cosine = 0.0
        else:
            cosine = float(np.dot(vec_a, vec_b) / (norm_a * norm_b))

        # 语义等级标签，方便前端直接渲染颜色
        if cosine >= 0.85:
            label = "高度相似"
        elif cosine >= 0.65:
            label = "语义相近"
        elif cosine >= 0.45:
            label = "话题相关"
        else:
            label = "语义疏远"

        cost_ms = int((time.time() - start_time) * 1000)
        print(f"🔬 [Similarity] '{req.text_a[:20]}' vs '{req.text_b[:20]}' => cosine={cosine:.4f} ({label}) | {cost_ms}ms")

        return {
            "code": 200,
            "msg": "success",
            "data": {
                "cosine": round(cosine, 6),
                "label": label,
                "costMs": cost_ms,
                # 向量数据量大，默认不返回；前端如需 2D 投影可开启
                # "vec_a": vec_a.tolist(),
                # "vec_b": vec_b.tolist(),
            }
        }
    except HTTPException:
        raise
    except Exception as e:
        print(f"❌ [Similarity] Error: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/ai/ltr/rank")
def ltr_rank(req: LTRRankRequest):
    """
    业务功能：接收候选文档的特征组，通过 LTR 模型（或合成权重基线）预估最终排名分数。
    请求体：features 包含多个文档的打分特征，如 es_score, knn_score, rrf_score 等字典列表。
    返回体：包含按输入顺序对应的排序分列表。
    """
    start_time = time.time()
    try:
        scores = ltr_manager.rank(req.features)
        cost_ms = int((time.time() - start_time) * 1000)
        # print(f"🔬 [LTR_Rank] Processed {len(req.features)} docs in {cost_ms}ms")
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "scores": scores,
                "costMs": cost_ms
            }
        }
    except Exception as e:
        import traceback
        traceback.print_exc()
        print(f"❌ [LTR_Rank] Error: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

# ─────────────────────────────────────────────────────────────────────────────
# 内部接口：文档索引预创建 + 写别名注册（轨道B：动态文档索引别名化）
# 调用方：Java DocIndexRoutingService.notifyPythonToEnsureIndex()（异步，非阻塞）
# 设计原理：
#   写别名命名规则：{physical_index}_write，如 kb_document_official_write
#   读操作统一走 kb_document 别名（Index Template 通配 kb_document_* 自动注册）
#   OutboxPoller 的 update_by_query 继续用物理索引名（精确定位，不走别名）
# ─────────────────────────────────────────────────────────────────────────────

class IndexEnsureRequest(BaseModel):
    indexName: str   # 目标物理索引名，如 "kb_document_official"

def _ensure_doc_index_with_alias(index_name: str) -> dict:
    """
    业务功能：确保文档物理索引存在，并自动注册对应写别名（幂等）。
    关键流程：
      1. 检查物理索引是否存在，不存在则创建（继承 kb_document_* Index Template）
      2. 检查写别名 {index_name}_write 是否挂载，未挂载则注册（is_write_index=True）
      3. 全局读别名 kb_document 由 Template 通配处理，无需手动注册

    @param index_name 目标物理索引名（必须以 kb_document_ 开头）
    @return dict 包含 created/alias_registered 两个布尔字段，用于日志追踪
    """
    from elasticsearch import Elasticsearch

    es_host = os.getenv("ES_HOST", "http://localhost:9200")
    es_user = os.getenv("ES_USERNAME", "")
    es_pass = os.getenv("ES_PASSWORD", "")

    # 构建 ES 客户端（局部创建，不污染全局状态）
    kwargs = {"hosts": [es_host], "request_timeout": 15}
    if es_user:
        kwargs["basic_auth"] = (es_user, es_pass)
    es = Elasticsearch(**kwargs)

    result = {"index_name": index_name, "created": False, "alias_registered": False}

    # Step 1: 确保物理索引存在（Template 已配置通配 kb_document_*，自动继承 Mapping）
    if not es.indices.exists(index=index_name):
        es.indices.create(index=index_name)
        result["created"] = True
        print(f"✅ [IndexEnsure] 物理索引已创建: {index_name}")
    else:
        print(f"ℹ️ [IndexEnsure] 物理索引已存在，跳过创建: {index_name}")

    # Step 2: 幂等注册写别名 {index_name}_write
    write_alias = f"{index_name}_write"
    try:
        existing = es.indices.get_alias(index=index_name)
        existing_aliases = existing.get(index_name, {}).get("aliases", {})
        if write_alias not in existing_aliases:
            es.indices.update_aliases(body={"actions": [
                {"add": {"index": index_name, "alias": write_alias, "is_write_index": True}}
            ]})
            result["alias_registered"] = True
            print(f"✅ [IndexEnsure] 写别名已注册: {write_alias} → {index_name}")
        else:
            print(f"ℹ️ [IndexEnsure] 写别名已存在，跳过: {write_alias}")
    except Exception as e:
        # 别名注册失败不阻断主流程：rag_pipeline 有降级逻辑（写别名不存在时回退物理索引名）
        print(f"⚠️ [IndexEnsure] 别名注册失败（不阻断）: {e}")

    return result


@app.post("/internal/index/ensure")
def ensure_index(req: IndexEnsureRequest, x_internal_token: Optional[str] = None):
    """
    业务功能：预创建文档 ES 物理索引并注册写别名（轨道B核心入口）。
    调用方：Java DocIndexRoutingService.notifyPythonToEnsureIndex()（异步调用，失败不影响注册）。
    关键流程：
      1. Internal Token 鉴权（防止外部误调用）
      2. 校验 indexName 前缀（必须是 kb_document_ 开头，拒绝任意索引名）
      3. 调用 _ensure_doc_index_with_alias() 创建索引并注册写别名

    @param req.indexName 目标物理索引名（必须以 kb_document_ 开头）
    @return { code, msg, data: { created, alias_registered } }
    """
    # Internal Token 校验（X-Internal-Token Header，FastAPI 自动映射下划线 ↔ 连字符）
    expected_token = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
    if x_internal_token != expected_token:
        raise HTTPException(status_code=403, detail="Forbidden: invalid internal token")

    index_name = (req.indexName or "").strip()
    # 安全门控：只允许操作 kb_document_ 前缀的文档索引
    if not index_name or not index_name.startswith("kb_document_"):
        raise HTTPException(
            status_code=400,
            detail=f"indexName 必须以 kb_document_ 开头，当前值: '{index_name}'"
        )

    try:
        result = _ensure_doc_index_with_alias(index_name)
        return {"code": 200, "msg": "success", "data": result}
    except Exception as e:
        print(f"❌ [IndexEnsure] 执行异常: {e}")
        raise HTTPException(status_code=500, detail=str(e))




# =============================================================================
# QA 流式对话端点
# 业务功能：接收来自 Java 网关的 Prompt（已包含检索到的知识底本），
#           通过底层 Ollama/OpenAI 兼容接口流式生成答案，使用 SSE 格式透传给前端。
# 关键流程：Java 端组装 Prompt -> POST /api/ai/llm/chat_stream -> yield token chunks -> Java 转发给 Vue
# 降级路径：任何阶段异常则发送 [ERROR] 事件行，Java 端可捕获并返回错误提示。
# =============================================================================

class ChatStreamRequest(BaseModel):
    messages: List[Dict[str, str]]  # OpenAI 消息格式 [{"role": "user"/"system", "content": "..."}]
    model_key: Optional[str] = "QA_LLM_MODEL"   # 指定模型的环境变量 key
    temperature: Optional[float] = 0.5
    max_tokens: Optional[int] = 1500

class ChatRequest(BaseModel):
    messages: List[Dict[str, str]]
    model_key: Optional[str] = "QA_LLM_MODEL"
    temperature: Optional[float] = 0.2
    max_tokens: Optional[int] = 800

from fastapi.responses import StreamingResponse
import requests
import json
import re
import time as _time

LLM_TRANSIENT_EXCEPTIONS = (
    requests.exceptions.SSLError,
    requests.exceptions.ConnectionError,
    requests.exceptions.Timeout,
    requests.exceptions.ChunkedEncodingError,
)

def _llm_error_code(error: Exception) -> str:
    text = str(error)
    if isinstance(error, requests.exceptions.Timeout) or "timed out" in text.lower():
        return "LLM_TIMEOUT"
    if isinstance(error, requests.exceptions.SSLError) or "ssl" in text.lower() or "eof" in text.lower():
        return "LLM_SSL_EOF"
    if isinstance(error, requests.exceptions.ConnectionError):
        return "LLM_CONNECTION_ERROR"
    return "LLM_CALL_FAILED"

def _post_llm_with_retry(llm_url, payload, headers, timeout, stream=False, max_retries=2):
    last_error = None
    for attempt in range(max_retries + 1):
        try:
            return requests.post(llm_url, json=payload, headers=headers, stream=stream, timeout=timeout)
        except LLM_TRANSIENT_EXCEPTIONS as exc:
            last_error = exc
            if attempt >= max_retries:
                raise
            delay = 0.3 * (2 ** attempt)
            print(f"[LLM Retry] attempt={attempt + 1} code={_llm_error_code(exc)} delay={delay:.1f}s error={exc}")
            _time.sleep(delay)
    if last_error:
        raise last_error
    raise RuntimeError("LLM request failed before execution")

def _extract_llm_stream_content(chunk: dict) -> str:
    """Extract visible answer text from common OpenAI-compatible stream shapes."""
    if not isinstance(chunk, dict):
        return ""
    choices = chunk.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0] or {}
        delta = first.get("delta") or {}
        message = first.get("message") or {}
        for value in (delta.get("content"), message.get("content"), first.get("text")):
            text = _normalize_llm_content_piece(value)
            if text:
                return text
    message = chunk.get("message")
    for value in (
        chunk.get("response"),
        chunk.get("content"),
        chunk.get("text"),
        message.get("content") if isinstance(message, dict) else None,
    ):
        text = _normalize_llm_content_piece(value)
        if text:
            return text
    return ""

def _normalize_llm_content_piece(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts = []
        for item in value:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                text = item.get("text") or item.get("content")
                if isinstance(text, str):
                    parts.append(text)
        return "".join(parts)
    return str(value)

def _build_llm_payload(messages, model_key, temperature, max_tokens, stream):
    llm_model = os.getenv(model_key or "QA_LLM_MODEL", os.getenv("LLM_MODEL", "qwen2.5:7b"))
    payload = {
        "model": llm_model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": stream
    }
    if os.getenv("LLM_SEND_ENABLE_THINKING", "false").lower() == "true":
        payload["enable_thinking"] = False
    return llm_model, payload

def _llm_headers():
    headers = {"Content-Type": "application/json"}
    api_key = os.getenv("LLM_API_KEY", "")
    if api_key and api_key.strip():
        headers["Authorization"] = f"Bearer {api_key.strip()}"
    return headers

@app.post("/api/ai/llm/chat")
def chat(req: ChatRequest):
    llm_url = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
    llm_model, payload = _build_llm_payload(
        req.messages,
        req.model_key or "QA_LLM_MODEL",
        req.temperature,
        req.max_tokens,
        False
    )
    try:
        resp = _post_llm_with_retry(llm_url, payload, _llm_headers(), timeout=120, stream=False)
        if resp.status_code != 200:
            return {"code": resp.status_code, "msg": f"LLM 返回异常 {resp.status_code}: {resp.text[:800]}", "data": None}
        raw_data = json.loads(resp.content.decode("utf-8"))
        content = _extract_llm_stream_content(raw_data).strip()
        if "<think>" in content:
            content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()
        return {"code": 200, "msg": "success", "data": {"model": llm_model, "content": content}}
    except Exception as e:
        print(f"[Chat] non-stream request failed: {e}")
        return {"code": 500, "msg": _llm_error_code(e), "data": None}

@app.post("/api/ai/llm/chat_stream")
def chat_stream(req: ChatStreamRequest):
    """
    业务功能：通过大模型流式回答基于知识库检索结果的用户问题（RAG 问答）
    
    接口行为：
      - 使用 Server-Sent Events (SSE) 格式逐 token 向下游推送文字
      - 每行格式为: data: <token text>\\n\\n
      - 结束时发送: data: [DONE]\\n\\n
    
    与 LLMClient.ask() 的区别：
      - ask() = 同步阻塞，等待 LLM 完整回答后一次性返回（用于 HyDE/重排）
      - chat_stream = 流式连接，边生成边推送（用于 QA 对话，减少用户等待感）
    """
    llm_url  = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
    llm_model, payload = _build_llm_payload(
        req.messages,
        req.model_key or "QA_LLM_MODEL",
        req.temperature,
        req.max_tokens,
        True
    )
    print(f"💬 [ChatStream] model_key={req.model_key or 'QA_LLM_MODEL'} model={llm_model} url={llm_url}")

    def generate():
        """
        生成器函数：逐行读取 Ollama 的 SSE 数据流，提取 delta.content 后 yield 给 HTTP 响应。
        """
        try:
            emitted_content = False
            with _post_llm_with_retry(llm_url, payload, _llm_headers(), timeout=120, stream=True) as resp:
                if resp.status_code != 200:
                    detail = ""
                    try:
                        detail = resp.text[:800]
                    except Exception:
                        detail = ""
                    print(
                        f"❌ [ChatStream] LLM error status={resp.status_code} "
                        f"model={llm_model} url={llm_url} body={detail}"
                    )
                    message = f"LLM 返回异常 {resp.status_code}"
                    if detail:
                        message += f": {detail}"
                    yield f"data: [ERROR] {message}\n\n"
                    return

                for line in resp.iter_lines():
                    if not line:
                        continue
                    # SSE 格式：每行以 "data: " 开头
                    raw = line.decode("utf-8") if isinstance(line, bytes) else line
                    if raw.startswith("data: "):
                        raw = raw[6:]
                    if raw.strip() == "[DONE]":
                        yield "data: [DONE]\n\n"
                        break
                    try:
                        chunk = json.loads(raw)
                        content_piece = _extract_llm_stream_content(chunk)
                        if content_piece:
                            # 剔除 Qwen3 系列残留的 <think>...</think> 片段
                            if "<think>" in content_piece:
                                content_piece = re.sub(r"<think>.*?</think>", "", content_piece, flags=re.DOTALL)
                            if content_piece:
                                emitted_content = True
                                yield f"data: {json.dumps(content_piece, ensure_ascii=False)}\n\n"
                    except (json.JSONDecodeError, KeyError):
                        continue  # 跳过无法解析的行，如心跳包等

                if not emitted_content:
                    print(f"[ChatStream] stream ended without visible answer content model={llm_model}")

        except Exception as e:
            print(f"❌ [ChatStream] streaming error: {e}")
            yield f"data: [ERROR] {_llm_error_code(e)}\n\n"

    return StreamingResponse(generate(), media_type="text/event-stream")


if __name__ == "__main__":
    from dotenv import load_dotenv
    load_dotenv()
    port = int(os.getenv("AI_PORT", 8001))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=False)

