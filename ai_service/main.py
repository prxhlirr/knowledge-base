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
    print("🚀 [Lifespan] 主动预加载 AI 模型层 (Embedding & Reranker)...")
    model_manager.load_model()

    # [冷启动优化] 预热 Jieba 结巴分词字典，消除第一个文档处理时的 0.5-1s 懒加载延迟。
    # 根因：Jieba 首次调用 extract_tags 时才从磁盘加载 prefix/suffix dict（约 0.635s），
    #       之后常驻内存。提前在 lifespan 触发一次，所有文档均可直接命中缓存。
    try:
        import jieba.analyse as _jieba_analyse
        _jieba_analyse.extract_tags("预热结巴分词字典初始化", topK=5)
        print("✅ [Lifespan] Jieba 字典预热完成")
    except Exception as _je:
        print(f"⚠️ [Lifespan] Jieba 预热失败（不影响主流程）: {_je}")


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

class RerankRequest(BaseModel):
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
def encode_query_cached(text: str):
    # [修复] BGE-M3 asymmetric 检索指令前缀仅对长查询有效：
    # 对 ≤15 字的短查询，指令前缀会将编码推向"正式文献检索模式"，
    # 反而压低与文学性/隐喻性表达（如"缄默"≈"保密"）的向量相似度（0.58→0.28）。
    # 长查询（>15字）词汇充分，指令前缀可正常增强 asymmetric 检索效果。
    if len(text.strip()) <= 15:
        return model_manager.encode(text)
    return model_manager.encode(BGE_QUERY_INSTRUCTION + text)

@app.post("/api/ai/vector/query")
def encode_query(req: QueryRequest):
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="Query text cannot be empty")
        
    start_time = time.time()
    try:
        # 使用缓存的推理方法
        vectors = encode_query_cached(req.text)
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
        # ≤15字的短查询不加前缀（指令前缀会将编码推向"正式文献检索模式"，
        #   压低隐喻/口语语义相似度，如「缄默」≈「保密」从 0.58 降至 0.28）；
        # >15字的长查询加指令前缀以增强 asymmetric passage retrieval 效果。
        actual_text = text if len(text) <= 15 else BGE_QUERY_INSTRUCTION + text

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
        all_vectors = model_manager.encode(segments)  # shape: (N, 1024)
        
        if all_vectors is None or len(all_vectors) == 0:
            raise HTTPException(status_code=500, detail="Encoding failed: empty vectors")

        # 均值池化得到文档级向量
        doc_vector = np.mean(all_vectors, axis=0).tolist()
        cost_ms = int((time.time() - start_time) * 1000)
        
        print(f"📄 [LongDocVector] text_len={len(text)} | segments={len(segments)} | cost={cost_ms}ms")
        
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "vector": doc_vector,
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
    llm_url = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
    llm_model = os.getenv("HYDE_LLM_MODEL", "qwen2.5:7b")  # ← 升级为 7B
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
    hypothetical_doc = query  # 降级默认值：LLM 失败时退回原始 query
    try:
        resp = req_lib.post(llm_url, json={
            "model": llm_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": hyde_prompt}
            ],
            "temperature": 0.2,
            "max_tokens": 120
        }, timeout=30.0)  # ← 7B 模型推理约 3-8s，加到 30s
        if resp.status_code == 200:
            content = resp.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
            # 剔除 deepseek-r1 的 <think>...</think> 思维链标签
            import re
            content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()
            if content and len(content) > 10:
                hypothetical_doc = content
                print(f"💡 [HyDE] '{query[:20]}' → 假设文档({len(hypothetical_doc)}字): '{hypothetical_doc[:60]}...'")
    except Exception as e:
        print(f"⚠️ [HyDE] LLM 扩展失败，退回原始 query 编码: {e}")

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

    llm_url = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
    # LLM Reranker 专门使用 7B 大模型（语义理解能力关键）
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

    try:
        resp = req_lib.post(llm_url, json={
            "model": llm_model,
            "messages": [
                {"role": "system", "content": "你是文档相关性评判专家。严格按格式输出，每行一个0-10的整数。"},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.0,  # 评分任务需要确定性输出
            # [解套6] 由于新版 Qwen 等模型自带 <think> 思维链机制，25 token 的额度刚思考就被斩断，
            # 导致提取到的分数全为 0.0。必须放宽到 1500 tokens 供它充分演绎。
            "max_tokens": 1500
        }, timeout=45.0)  # 7B 批量推理最多 45s

        if resp.status_code == 200:
            content = resp.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
            
            # [安全网] 如果输出被掐断甚至只有一段孤立的 <think>，说明超时或异常
            if '<think>' in content:
                if '</think>' in content:
                    content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()
                else:
                    # 连思链都没写完，说明被强制截断，直接降级抛给 ColBERT
                    print(f"⚠️ [LLM Reranker] <think> not finished, fallback to ColBERT.")
                    return {"code": 200, "msg": "fallback", "data": {"scores": None, "costMs": 0, "model": "fallback"}}

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
        import requests
        # 指向本地的轻量级 Ollama 或者 vLLM 网关
        llm_url = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
        llm_model = os.getenv("LLM_MODEL", "qwen2.5:7b") # 建议部署 qwen2.5 1.5B/7B
        
        # [自适应防崩] 如果本地没有配置环境变量，探测可用模型
        if "LLM_MODEL" not in os.environ:
            try:
                base_url = llm_url.replace("/v1/chat/completions", "/api/tags")
                tags_res = requests.get(base_url, timeout=1.0)
                if tags_res.status_code == 200:
                    models = tags_res.json().get("models", [])
                    qwen_models = [m["name"] for m in models if "qwen" in m["name"].lower()]
                    llm_model = qwen_models[0] if qwen_models else (models[0]["name"] if models else llm_model)
            except:
                pass
                
        payload = {
            "model": llm_model,
            "messages": [
                {"role": "system", "content": "You are a helpful text classification assistant."},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.1,  # 极低温度换取稳定性
            "max_tokens": 15
        }
        
        # [超时策略] deepseek-r1 系列先输出 <think>思维链</think> 约需 3~5s，
        # 4s 超时必然截断导致回退；改为 8s 可承接完整推理链而不影响用户体验。
        resp = requests.post(llm_url, json=payload, timeout=8.0)
        if resp.status_code == 200:
            content = resp.json().get('choices', [{}])[0].get('message', {}).get('content', '').strip()
            
            # [关键修复] 处理 deepseek-r1 的 <think>推理</think>答案 输出格式
            import re
            if '<think>' in content:
                if '</think>' in content:
                    # 完整思链：剥离 <think>...</think>，只取答案部分
                    content = re.sub(r'<think>.*?</think>', '', content, flags=re.DOTALL).strip()
                else:
                    # 超时导致思链未完成（截断），内容不可用，降级返回原始查询
                    print(f"⚠️ [LLM Rewrite] <think> block incomplete. Fallback to original.")
                    return {"code": 200, "data": query}
            
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
            print(f"⚠️ [LLM Router HTTP Error] {resp.status_code}")
    except requests.exceptions.Timeout:
         print(f"⚠️ [LLM Router Timeout] Gateway breached 4s limit for: {query}")
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
    need_vector: bool = True  # 是否需要 HyDE 向量（长查询/禁用向量时可跳过）

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

    # 命中合并缓存，直接返回
    cache_key = query + ("_v" if req.need_vector else "_nv")
    if cache_key in _rewrite_hyde_cache:
        cached = _rewrite_hyde_cache[cache_key]
        print(f"🎯 [RewriteHyDE Cache] '{query[:20]}' hit, cost 0ms")
        return {"code": 200, "data": cached}

    # 防御性泛化拦截：
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
            import requests as _req_lib
            resp = _req_lib.post(llm_url, json={
                "model": llm_model,
                "messages": [
                    {"role": "system", "content": hyde_system_prompt},
                    {"role": "user",   "content": hyde_user_prompt}
                ],
                "temperature": 0.3,
                "max_tokens": 150
            }, timeout=30.0)  # 后台可以等更久，不影响用户感知

            if resp.status_code == 200:
                raw = resp.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
                raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.DOTALL).strip()
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
    业务功能：为指定的政务/法律条文内容，生成用户可能提问的口语化问题列表。
    关键流程：fine chunk 内容 → LLM 生成 3-5 个口语化问题 → 返回问题列表
    领域覆盖：公安执法、行政管理、法律法规、政务公文、卧床医疗健康、安全生产、社会保障等政府领域
    降级路径：LLM 失败时返回空列表，由调用方决定是否跳过该条文
    """
    import requests as req_lib
    import re
    chunk = req.text.strip()
    if not chunk or len(chunk) < 8:
        return {"code": 200, "data": []}

    llm_url = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
    llm_model = os.getenv("LLM_MODEL", "qwen2.5:7b")

    # 系统提示：明确领域 + 明确输出格式
    system_prompt = (
        "你是一位政府文件检索专家，熟悉普通市民如何用口语提问政务/法律条文。"
        "领域覆盖：公安执法、刑事司法、行政处罚、商事调解、医疗卫生、安全生产、社会保障、环境保护、市场监管、教育民政等。"
        "输出格式规定：直接输出2-3个问题，每行仅一个问题，绝对不加编号，不加任何解释。"
    )
    # few-shot 示例：明确覆盖费用/资金/资产角度
    prompt = (
        f"# 示例\n"
        f"条文：设立商事调解组织应当符合：（四）有30万元以上的资产。\n"
        f"口语化问题（每行一个，不要编号）：\n"
        f"成立这家机构最少需要多少钱？\n"
        f"开办这个调解机构需要准备多少资金？\n"
        f"\n"
        f"# 当前任务\n"
        f"条文：{chunk}\n"
        f"口语化问题（每行一个，不要编号）："
    )

    try:
        resp = req_lib.post(llm_url, json={
            "model": llm_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.3,
            "max_tokens": 100
        }, timeout=45.0)
        if resp.status_code == 200:
            content = resp.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
            content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()
            # 先按换行分割，再用中文问号进一步切分（处理同行多问题拼接问题）
            parts = []
            for line in content.split("\n"):
                line = line.strip()
                if not line:
                    continue
                # 若同一行有多个问句（用？分割），再丌分
                sub = re.split(r"(?<=？)\s*", line)
                parts.extend(sub)
            cleaned = []
            for p in parts:
                q = re.sub(r"^[\d１-９\uff0e\u3001\-\.\*\u25ca\u30fb#\s]+", "", p).strip()
                if 5 <= len(q) <= 60 and q not in cleaned:
                    cleaned.append(q)
                if len(cleaned) >= 2:
                    break
            print(f"  [Q&A Gen] {len(cleaned)} 个问题 | Chunk: '{chunk[:30]}...'")
            return {"code": 200, "data": cleaned}
    except Exception as e:
        print(f"  [Q&A Gen] LLM 失败: {e}")

    return {"code": 200, "data": []}


# ─────────────────────────────────────────────────────────────────────────────
# QA 检索接口：用 dense 向量在 ES QA 索引中 KNN 召回高置信度问答对
# 供 Java QaInjectionStep 调用，将高相关 QA 注入候选池 HEAD
# ─────────────────────────────────────────────────────────────────────────────

class QaSearchRequest(BaseModel):
    vector: List[float]          # dense query 向量（1024 维，由 Java 侧预取）
    query_text: str              # 原始查询词（用于 bigram 重叠校验）
    force_source: Optional[str] = None  # 租户数据源过滤（对应 metadata.source）
    top_k: int = 3               # 最多返回几条 QA

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
        qa_index = os.getenv("QA_INDEX_PATTERN", "kb_qa_*")
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
            "_source": ["question", "answer_content", "answer_chunk_id", "section_path", "source"]
        }

        # 租户数据源过滤（若指定 force_source）
        if req.force_source:
            knn_query["filter"] = {
                "term": {"metadata.source": req.force_source}
            }

        resp = _req.post(
            f"{es_host}/{qa_index}/_search",
            json=knn_query,
            auth=_es_auth,      # None 时 requests 自动跳过 Authorization 头
            timeout=3.0,
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
            results.append({
                "_id":      hit.get("_id"),
                "_source":  source,
                "_score":   score,
                "_rrf_score": rrf_score,
                "content":  source.get("answer", source.get("question", ""))
            })

        cost_ms = int((time.time() - start_time) * 1000)
        print(f"  [QA Search] top_k={req.top_k} hits={len(results)} | Cost: {cost_ms}ms")
        return {"code": 200, "data": results, "costMs": cost_ms}

    except Exception as e:
        print(f"  [QA Search] 检索异常（降级返回空）: {e}")
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
        resp = req_lib.post(llm_url, json={
            "model": llm_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user",   "content": prompt}
            ],
            "temperature": 0.4,
            "max_tokens": 80
        }, timeout=45.0)
        if resp.status_code == 200:
            # [编码修复] 强制用 resp.content.decode('utf-8') 而非 resp.json()
            # 根因：Ollama 响应头通常为 'application/json' 而非 'application/json; charset=utf-8'
            #   requests 库在无 charset 声明时，默认将编码推断为 ISO-8859-1
            #   导致 resp.text / resp.json() 内层第三个节的中文字符被误读为 3 个拉丁字符（乱码）
            #   修复：用 bytes 原文强制 UTF-8 解码，和 Ollama 输出编码一致
            import json as _json
            raw_data = _json.loads(resp.content.decode('utf-8'))
            content = raw_data.get("choices", [{}])[0].get("message", {}).get("content", "").strip()
            # 去除 deepseek-r1 风格的 <think> 标签
            content = _re.sub(r"<think>.*?</think>", "", content, flags=_re.DOTALL).strip()
            phrases = []
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

if __name__ == "__main__":
    from dotenv import load_dotenv
    load_dotenv()
    port = int(os.getenv("AI_PORT", 8001))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=False)
