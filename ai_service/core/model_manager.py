import os
import math
import unicodedata
import numpy as np
from pathlib import Path
from transformers import AutoTokenizer
import onnxruntime
from onnxruntime.capi.onnxruntime_inference_collection import InferenceSession
from typing import List, Union
import time
import threading
import gc

# ── ES rank_features 合法性校验常量（模块级，只构建一次）────────────────────────
# ES 8.x rank_features 字段对 key（feature name）的全量禁止字符集：
#   1. '.'（0x2E）        → ES 将其解析为嵌套字段路径分隔符，直接导致整个 document
#                           "failed to parse"（ES 官方文档明确禁止）
#   2. 空白字符            → ES field-name 规范不允许；产生无语义 key
#   3. ASCII 控制字符      → 0x00-0x1F（含 null byte）和 0x7F(DEL);
#                           Java ES client 在注册 field-name 时会拒绝；
#                           Python json.dumps 虽会转义为 \uXXXX，但 ES 内部
#                           field-name 解析器会拒绝含控制字符的字段名
# 注意：除 '.' 之外的普通标点（','、':'、'/'、'%' 等）ES 均接受，不在此列。
_RF_BANNED_CHARS: frozenset = frozenset(
    '.'
    + ' \t\r\n'                                  # 空白字符
    + ''.join(chr(i) for i in range(0x00, 0x20))  # ASCII 控制字符 0x00-0x1F
    + chr(0x7F)                                     # DEL
)

# ── 稀疏向量语义有效性校验（独立于 ES 合法性，解决另一个维度的问题）────────────
# 问题根因（第一性原理）：
#   我们用 L2 Norm 近似 SPLADE 权重。真实 SPLADE 中，标点符号经过 vocab_projection
#   + ReLU 后权重趋近 0（无语义区分力）；但 L2 Norm 近似无法复现这一效果：
#   `。` 在 Transformer 中是极高频 token，模型对其有稳定、集中的表示（高 L2 Norm），
#   导致 `"。": 0.7116` 这类高权重标点污染稀疏向量，引发以下问题：
#
#   1. 【零区分力】`。` 出现在所有文档每个句子末尾，对所有文档权重都高，
#      不能区分「苹果产量报告」和「行政许可法规」，是纯噪声。
#   2. 【Query-Doc 不对称】用户搜索时不输入标点，query 稀疏向量无 `。`；
#      但大量文档含高权重 `。`，造成无效的相关性分数膨胀。
#   3. 【语义信号污染】标点权重挤占了 top_k 名额（默认64），
#      压缩了真正有意义词汇（行政、许可、申请等）的表示空间。
#
# 解决方案：Unicode category 过滤。
#   Python unicodedata.category() 将 Unicode 字符分类为：
#     L* = Letter（字母）: Lu/Ll/Lt/Lm/Lo（Lo 包含全部 CJK 汉字！）
#     N* = Number（数字）: Nd/Nl/No
#     P* = Punctuation（标点）: 含 Po（。，！？；：）、Pd（—-）、Ps/Pe（「」）等
#     S* = Symbol（符号）: 含 Sm（+<=）、So（©→）等
#   规则：一个 token 只有包含至少 1 个 L*/N* 字符，才具备词汇检索价值。
#   覆盖范围：
#     `。` → Po → 排除  `，` → Po → 排除  `—` → Pd → 排除
#     `/`  → Po → 排除  `--` → Pd → 排除  `%` → Po → 排除
#     `张` → Lo → 保留  `20` → Nd → 保留  `人民代表大会` → Lo → 保留
def _has_lexical_content(token: str) -> bool:
    """
    业务功能：判断解码后的 token 是否含有实义词素（字母或数字），用于稀疏向量语义过滤。
    关键流程：遍历 token 每个字符，只要有一个字符的 Unicode category 以 'L'（字母，含汉字）
              或 'N'（数字）开头，即认定该 token 有词汇检索价值，返回 True；
              全部为标点/符号/空白则返回 False。
    设计原则：不枚举具体标点字符（枚举必然有遗漏），而是用 Unicode category 做语言无关的
              通用判断，天然覆盖中英文标点、全角符号、破折号、省略号等所有情形。
    """
    return any(unicodedata.category(c)[0] in ('L', 'N') for c in token)

class ModelManager:
    """管理 BGE-m3 等编码模型的单例类 (GPU 优先 + CPU 自动回退版)"""
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(ModelManager, cls).__new__(cls)
            cls._instance.model = None
            cls._instance.cpu_model = None
            cls._instance.reranker = None
            cls._instance.cpu_reranker = None
            cls._instance.tokenizer = None
            cls._instance.rerank_tokenizer = None
            
            # --- 动态路径处理 ---
            # 优先从环境变量 MODEL_BASE_PATH 获取，若未配置则默认指向 Docker 标准挂载路径
            base_model_path = Path(os.getenv("MODEL_BASE_PATH", "/app/models/onnx_native"))
            
            cls._instance.model_path = base_model_path / "bge-m3" / "model.onnx"
            cls._instance.int8_model_path = base_model_path / "bge-m3" / "model_int8.onnx" # 优先检测量化模型
            cls._instance.reranker_path = base_model_path / "bge-reranker-v2-m3" / "onnx" / "model.onnx"
            cls._instance.int8_reranker_path = base_model_path / "bge-reranker-v2-m3" / "onnx" / "model_int8.onnx"
            cls._instance.tokenizer_dir = base_model_path / "bge-m3"
            cls._instance.reranker_tokenizer_dir = base_model_path / "bge-reranker-v2-m3"
            
            # [Fix-D] max_len 动态对齐 MAX_CHUNK_SIZE 环境变量，消除大 chunk 的语义盲区。
            # 根因：coarse chunk 最大 500 字 ≈ 750 tokens，被截断至前 340 字（512 token≈340汉字），
            #       后 160 字进入 BM25 content 字段但不进向量，形成"BM25 全文/KNN 仅首段"的语义盲区。
            # 修复：chunk 大时自动扩 max_len 至 1024（≈700汉字），覆盖 500 字 coarse chunk 全文。
            _max_chunk = int(os.getenv('MAX_CHUNK_SIZE', '300'))
            cls._instance.max_len = 512 if _max_chunk <= 300 else 1024
            # Reranker 独立 max_len: 256 足够精排, Attention O(256²) vs O(512²) 快 4x
            cls._instance.rerank_max_len = 256
            cls._instance.device = "cpu"
            cls._instance.reranker_device = "cpu"
            cls._instance._rerank_cache = {}
            cls._instance._embedding_cache = {}
            cls._instance._embedding_load_lock = threading.RLock()
            cls._instance._reranker_load_lock = threading.RLock()
            cls._instance.last_used_at = 0.0
        return cls._instance
        
    def _get_cuda_options(self, device_id: int):
        """抽取 CUDA 配置逻辑，便于多卡复用"""
        try:
            import subprocess as _sp
            _nv = _sp.run(
                ['nvidia-smi', f'--id={device_id}',
                 '--query-gpu=memory.free',
                 '--format=csv,noheader,nounits'],
                capture_output=True, text=True, timeout=5
            )
            if _nv.returncode == 0:
                _free_mb = int(_nv.stdout.strip())
                _gpu_mem_limit = int(_free_mb * 0.8) * 1024 * 1024
            else:
                _gpu_mem_limit = int(os.getenv('ORT_GPU_MEM_LIMIT_GB', '4')) * 1024 ** 3
        except Exception:
            _gpu_mem_limit = int(os.getenv('ORT_GPU_MEM_LIMIT_GB', '4')) * 1024 ** 3

        return {
            'device_id': device_id,
            'arena_extend_strategy': 'kSameAsRequested',
            'gpu_mem_limit': _gpu_mem_limit,
            'cudnn_conv_algo_search': 'DEFAULT',
            'do_copy_in_default_stream': True,
            'enable_cuda_graph': False,
        }

    def touch_last_used(self):
        self.last_used_at = time.time()

    def get_idle_seconds(self) -> float:
        if not getattr(self, "last_used_at", 0.0):
            return 0.0
        return max(0.0, time.time() - self.last_used_at)

    def _new_session_options(self):
        sess_options = onnxruntime.SessionOptions()
        sess_options.execution_mode = onnxruntime.ExecutionMode.ORT_SEQUENTIAL
        sess_options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_ALL
        sess_options.enable_mem_pattern = True
        sess_options.add_session_config_entry("session.use_mmap", "1")
        sess_options.log_severity_level = 3
        return sess_options

    def _provider_pair(self):
        sess_options = self._new_session_options()
        available_providers = onnxruntime.get_available_providers()
        print(f"🔍 Available ORT Providers: {available_providers}")
        if 'CUDAExecutionProvider' in available_providers:
            emb_gpu_id = int(os.getenv('EMBEDDING_GPU_ID', os.getenv('ORT_CUDA_DEVICE_ID', '0')))
            rerank_gpu_id = int(os.getenv('RERANKER_GPU_ID', os.getenv('ORT_CUDA_DEVICE_ID', '0')))
            return (
                [('CUDAExecutionProvider', self._get_cuda_options(emb_gpu_id)), 'CPUExecutionProvider'],
                [('CUDAExecutionProvider', self._get_cuda_options(rerank_gpu_id)), 'CPUExecutionProvider'],
                sess_options,
                sess_options,
            )
        if 'DmlExecutionProvider' in available_providers:
            return (['DmlExecutionProvider', 'CPUExecutionProvider'], ['DmlExecutionProvider', 'CPUExecutionProvider'], sess_options, sess_options)
        return (['CPUExecutionProvider'], ['CPUExecutionProvider'], sess_options, sess_options)

    def load_embedding_model(self):
        start_load = time.time()
        emb_providers, _, sess_options, _ = self._provider_pair()
        print(f"📦 Loading Embedding Model (preferred: {emb_providers[0]})")
        self.model = self._load_session_with_fallback(
            self.int8_model_path, self.model_path,
            emb_providers, sess_options, "Embedding"
        )
        self.tokenizer = AutoTokenizer.from_pretrained(str(self.tokenizer_dir.absolute()))
        self.cpu_model = self.model
        if self.model:
            act = self.model.get_providers()
            self.device = "CUDA" if "CUDAExecutionProvider" in act else ("DML" if "DmlExecutionProvider" in act else "CPU")
            print(f"🎯 [ModelManager] Embedding 实际运行设备: {self.device}")
            self._warmup_embedding()
        print(f"✨ Embedding initialization finished in {int((time.time() - start_load)*1000)}ms")

    def load_reranker_model(self):
        start_load = time.time()
        _, rerank_providers, _, rerank_sess_options = self._provider_pair()
        print(f"📦 Loading Reranker Model (preferred: {rerank_providers[0]})")
        self.reranker = self._load_session_with_fallback(
            self.int8_reranker_path, self.reranker_path,
            rerank_providers, rerank_sess_options, "Reranker"
        )
        self.rerank_tokenizer = AutoTokenizer.from_pretrained(str(self.reranker_tokenizer_dir.absolute()))
        self.cpu_reranker = self.reranker
        if self.reranker:
            act = self.reranker.get_providers()
            self.reranker_device = "CUDA" if "CUDAExecutionProvider" in act else ("DML" if "DmlExecutionProvider" in act else "CPU")
            print(f"🎯 [ModelManager] Reranker 实际运行设备: {self.reranker_device}")
            self._warmup_reranker()
        print(f"✨ Reranker initialization finished in {int((time.time() - start_load)*1000)}ms")

    def ensure_embedding_loaded(self):
        self.touch_last_used()
        if self.model is not None and self.tokenizer is not None:
            return
        with self._embedding_load_lock:
            if self.model is None or self.tokenizer is None:
                self.load_embedding_model()

    def ensure_reranker_loaded(self):
        self.touch_last_used()
        if self.reranker is not None and self.rerank_tokenizer is not None:
            return
        with self._reranker_load_lock:
            if self.reranker is None or self.rerank_tokenizer is None:
                self.load_reranker_model()

    def unload_embedding(self):
        with self._embedding_load_lock:
            self.model = None
            self.cpu_model = None
            self.tokenizer = None
            self._embedding_cache.clear()
            self.device = "cpu"
        gc.collect()

    def unload_reranker(self):
        with self._reranker_load_lock:
            self.reranker = None
            self.cpu_reranker = None
            self.rerank_tokenizer = None
            self._rerank_cache.clear()
            self.reranker_device = "cpu"
        gc.collect()

    def unload_all(self):
        self.unload_embedding()
        self.unload_reranker()
        print("[ModelManager] 模型对象已释放；CUDA 显存是否立即归还取决于运行时上下文。")

    def _warmup_embedding(self):
        if self.model:
            print("🔥 Warmup Embedding...")
            self.encode("warmup text")

    def _warmup_reranker(self):
        if self.reranker:
            print("🔥 Warmup Reranker (batch=5, warmup CUDA kernel for real workload)...")
            warmup_docs = ["预热文档：" + "测试内容" * 20] * 5
            self.rerank("预热查询：商事调解相关政策规定", warmup_docs)

    def load_model(self):

        """加载模型：自动探明运行环境 (CUDA/DML/CPU) 并分配算力，int8失败时回退fp32保持GPU"""
        start_load = time.time()
        
        device_id = int(os.getenv("ORT_CUDA_DEVICE_ID", 0))
        
        sess_options = onnxruntime.SessionOptions()
        sess_options.execution_mode = onnxruntime.ExecutionMode.ORT_SEQUENTIAL
        sess_options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_ALL
        sess_options.enable_mem_pattern = True
        sess_options.add_session_config_entry("session.use_mmap", "1")
        sess_options.log_severity_level = 3 
        
        try:
            available_providers = onnxruntime.get_available_providers()
            print(f"🔍 Available ORT Providers: {available_providers}")
            
            # --- 确定目标 Provider 列表 ---
            if 'CUDAExecutionProvider' in available_providers:
                # 分别获取两个模型的 GPU ID
                emb_gpu_id = int(os.getenv('EMBEDDING_GPU_ID', os.getenv('ORT_CUDA_DEVICE_ID', '0')))
                rerank_gpu_id = int(os.getenv('RERANKER_GPU_ID', os.getenv('ORT_CUDA_DEVICE_ID', '0')))
                
                emb_providers = [('CUDAExecutionProvider', self._get_cuda_options(emb_gpu_id)), 'CPUExecutionProvider']
                rerank_providers = [('CUDAExecutionProvider', self._get_cuda_options(rerank_gpu_id)), 'CPUExecutionProvider']
                
                print(f"🚀 [路径A] CUDA 可用！Embedding->GPU[{emb_gpu_id}], Reranker->GPU[{rerank_gpu_id}]")
                sess_options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_ALL
                rerank_sess_options = sess_options
            elif 'DmlExecutionProvider' in available_providers:
                emb_providers = rerank_providers = ['DmlExecutionProvider', 'CPUExecutionProvider']
                rerank_sess_options = sess_options
                print(f"🚀 [路径B] DirectML 可用！")
            else:
                emb_providers = rerank_providers = ['CPUExecutionProvider']
                rerank_sess_options = sess_options
                print(f"⚠️ [路径C] 仅 CPU 可用")

            # --- 1. 加载 Embedding 模型 ---
            print(f"📦 Loading Embedding Model (preferred: {emb_providers[0]})")
            self.model = self._load_session_with_fallback(
                self.int8_model_path, self.model_path, 
                emb_providers, sess_options, "Embedding"
            )
            self.tokenizer = AutoTokenizer.from_pretrained(str(self.tokenizer_dir.absolute()))
            self.cpu_model = self.model

            # --- 2. 加载 Reranker 模型 ---
            print(f"📦 Loading Reranker Model (preferred: {rerank_providers[0]})")
            self.reranker = self._load_session_with_fallback(
                self.int8_reranker_path, self.reranker_path,
                rerank_providers, rerank_sess_options, "Reranker"
            )
            self.rerank_tokenizer = AutoTokenizer.from_pretrained(str(self.reranker_tokenizer_dir.absolute()))
            self.cpu_reranker = self.reranker

            # --- 3. 设备状态汇报 ---
            if self.model:
                act = self.model.get_providers()
                self.device = "CUDA" if "CUDAExecutionProvider" in act else ("DML" if "DmlExecutionProvider" in act else "CPU")
            if self.reranker:
                act = self.reranker.get_providers()
                self.reranker_device = "CUDA" if "CUDAExecutionProvider" in act else ("DML" if "DmlExecutionProvider" in act else "CPU")
            
            print(f"🎯 [ModelManager] 实际运行设备: Embedding={self.device}, Reranker={self.reranker_device}")

            self._warmup_internal()

        except Exception as e:
            print(f"❌ CRITICAL LOAD FAILED: {e}")
            import traceback
            traceback.print_exc()
            with open("onnx_crash.log", "w", encoding="utf-8") as f:
                f.write(traceback.format_exc())
            self.model = None
            self.reranker = None
        finally:
            print(f"✨ Initialization finished in {int((time.time() - start_load)*1000)}ms")

    def _load_session_with_fallback(self, path_int8, path_fp32, providers, sess_options, label):
        """
        业务功能：带分级回退的 ONNX 模型会话加载器
        关键流程：
          1. CUDA 路径：直接加载 fp32 模型（跳过 int8）
             └─ 根因：int8 ONNX 的 QLinearMatMul / MatMulInteger 算子在 ORT 中仅被
                 CPUExecutionProvider 支持，CUDA 请求将导致所有算子静默回退 CPU。
          2. CPU 路径：int8 优先，失败回退 fp32
          3. fp32 加载后校验实际 Provider，打印警告信息
        """
        from os.path import normpath

        # 提取主目标 provider 名称（兼容字符串和元组两种格式）
        primary_provider = providers[0] if isinstance(providers[0], str) else providers[0][0]
        is_cuda_target = (primary_provider == 'CUDAExecutionProvider')

        # Step1: int8 量化模型（CUDA 路径跳过）
        # [CUDA 修复] int8 ONNX 不支持 CUDA 查询，强行加载会导致所有算子静默倒退到 CPU。
        if path_int8.exists() and not is_cuda_target:
            try:
                sess = InferenceSession(
                    normpath(str(path_int8.absolute())),
                    sess_options=sess_options,
                    providers=providers
                )
                actual = sess.get_providers()
                if primary_provider in actual:
                    print(f"✅ [{label}] int8 + {primary_provider} 加载成功 | actual={actual}")
                    return sess
                else:
                    print(f"⚠️ [{label}] int8 Provider 静默降级 actual={actual}，回退 fp32")
            except Exception as e:
                print(f"⚠️ [{label}] int8 加载异常: {e}，回退 fp32")
        elif is_cuda_target:
            print(f"⏩ [{label}] CUDA 路径跳过 int8（QLinearMatMul 算子 CUDA 不支持），直接使用 fp32")
        else:
            print(f"⚠️ [{label}] int8 模型文件不存在，直接使用 fp32")

        # Step2: 加载 fp32 模型
        sess = InferenceSession(
            normpath(str(path_fp32.absolute())),
            sess_options=sess_options,
            providers=providers
        )
        actual = sess.get_providers()
        if primary_provider in actual:
            print(f"✅ [{label}] fp32 + {primary_provider} 加载成功 | actual={actual}")
        else:
            print(f"⚠️ [{label}] fp32 也未能走到 {primary_provider}！actual={actual}")
            print(f"   可能原因：VRAM 不足 / CUDA 版本不匹配 / TensorRT 冲突")
        return sess



    def warmup(self):
        """手动触发预热"""
        self._warmup_internal()

    def _warmup_internal(self):
        """内部模型预热"""
        if self.model: 
            print("🔥 Warmup Embedding...")
            self.encode("warmup text")
        if self.reranker: 
            print("🔥 Warmup Reranker (batch=5, warmup CUDA kernel for real workload)...")
            # 使用 5 个文档、真实长度的 warmup，让 CUDA 预分配正确形状的 kernel
            # 避免首次真实请求 (5 docs) 触发 CUDA 重新分配，消除 5-10s 冷启动
            warmup_docs = ["预热文档：" + "测试内容" * 20] * 5
            self.rerank("预热查询：商事调解相关政策规定", warmup_docs)

    def encode(self, texts: Union[List[str], str]) -> List[List[float]]:
        """执行稠密向量编码"""
        if isinstance(texts, str): texts = [texts]
        self.ensure_embedding_loaded()
        if self.model is None: return []

        # 简单的内存缓存逻辑 (针对单条文本)
        if len(texts) == 1 and texts[0] in self._embedding_cache:
            return [self._embedding_cache[texts[0]]]

        inputs = self.tokenizer(texts, padding=True, truncation=True, max_length=self.max_len, return_tensors="np")
        model_input_names = [i.name for i in self.model.get_inputs()]
        input_feed = {k: v.astype(np.int64) for k, v in inputs.items() if k in model_input_names}
        
        try:
            output_names = [o.name for o in self.model.get_outputs()]
            outputs = self.model.run(output_names, input_feed)
            
            # BGE-M3 的输出维度通常为 [batch, seq_len, dim] (ndim=3) 或直接 [batch, dim] (ndim=2)
            feat = outputs[0]
            embeddings = feat[:, 0, :] if feat.ndim == 3 else feat
                
            norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
            normalized_embeddings = (embeddings / (norms + 1e-9)).tolist()
            
            # 单项缓存逻辑
            if len(texts) == 1:
                if len(self._embedding_cache) > 1000: self._embedding_cache.clear()
                self._embedding_cache[texts[0]] = normalized_embeddings[0]
                
            return normalized_embeddings
            
        except Exception as e:
            # --- 自动降级逻辑: 针对 DirectML 算子崩溃进行 CPU 回退重试 ---
            is_dml = "DML" in self.device
            print(f"⚠️ Embedding Run Failed (Device={self.device}): {e}")
            
            if is_dml:
                print("🔄 [Fallback] GPU Execution Failed. Retrying on CPU for stability...")
                try:
                    if self.cpu_model:
                        outputs = self.cpu_model.run(None, input_feed)
                        feat = outputs[0]
                        embeddings = feat[:, 0, :] if feat.ndim == 3 else feat
                        norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
                        return (embeddings / (norms + 1e-9)).tolist()
                    else:
                        # 备用方案：如果cpu_model未加载，创建临时会话
                        m_path = os.path.normpath(str(self.model_path.absolute()))
                        cpu_sess = InferenceSession(m_path, providers=['CPUExecutionProvider'])
                        outputs = cpu_sess.run(None, input_feed)
                        feat = outputs[0]
                        embeddings = feat[:, 0, :] if feat.ndim == 3 else feat
                        norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
                        return (embeddings / (norms + 1e-9)).tolist()
                except Exception as ex:
                    print(f"❌ [Fallback Failed] CPU retry also failed: {ex}")
            
            import traceback
            traceback.print_exc()
            return []

    def encode_colbert(self, texts: Union[List[str], str]) -> List[List[List[float]]]:
        """
        业务功能：BGE-M3 ColBERT 多向量编码（Late Interaction）
        原理：BGE-M3 ONNX 模型已输出 last_hidden_state（全量 token 向量），
              本方法提取所有有效 token（去除 padding），L2 归一化后返回。
        关键流程：tokenize → ONNX 推理 → 取 last_hidden_state → 去 padding → L2 归一化
        与 encode() 区别：encode() 只取 CLS token（[:, 0, :]）; 
                          encode_colbert() 返回全部非 padding token 的向量列表。
        用途：ColBERT MaxSim 评分，替代 BGE-Reranker Cross-Encoder，
              解决跨文体语义匹配（「缄默」≈「保密」）评分弱的问题。
        """
        if isinstance(texts, str):
            texts = [texts]
        self.ensure_embedding_loaded()
        if self.model is None:
            return []

        inputs = self.tokenizer(texts, padding=True, truncation=True,
                                max_length=self.max_len, return_tensors="np")
        model_input_names = [i.name for i in self.model.get_inputs()]
        input_feed = {k: v.astype(np.int64) for k, v in inputs.items() if k in model_input_names}

        try:
            # 只请求 last_hidden_state（节省计算 tanh/dense head 的开销）
            outputs = self.model.run(["last_hidden_state"], input_feed)
            hidden = outputs[0]  # [batch, seq_len, 1024]

            # L2 归一化每个 token 向量（ColBERT 标准做法）
            norms = np.linalg.norm(hidden, axis=-1, keepdims=True)  # [batch, seq_len, 1]
            normed = hidden / np.maximum(norms, 1e-9)               # [batch, seq_len, 1024]

            # 用 attention_mask 去除 padding token，只保留真实 token
            attention_mask = inputs["attention_mask"]  # [batch, seq_len]

            result = []
            for i in range(len(texts)):
                valid_mask = attention_mask[i].astype(bool)      # [seq_len]
                valid_vecs = normed[i][valid_mask].tolist()       # [valid_tokens, 1024]
                result.append(valid_vecs)
            return result

        except Exception as e:
            print(f"⚠️ [ColBERT] encode_colbert failed: {e}")
            import traceback
            traceback.print_exc()
            return [[] for _ in texts]

    def encode_sparse(self, texts: Union[List[str], str], top_k: int = 64) -> List[dict]:
        """
        业务功能：BGE-M3 稀疏向量编码（SPLADE 风格），兼容 ES rank_features 格式。
        核心原理：
          BGE-M3 的 ONNX 模型已输出 last_hidden_state [batch, seq_len, 1024]。
          对每个有效 token 的 embedding 第 0 维做 ReLU 激活，得到该 token 的权重。
          再通过 tokenizer 将 token_id 映射回词字符串，并按词累加权重（同词多次出现取最大值，
          符合 SPLADE Max 策略），最后保留 top_k 个非零权重词，返回 Dict[str, float]。
        与 dense encode 的区别：
          dense  → CLS token 的 embedding 均值池化（1024 维稠密浮点向量）
          sparse → 每个 token 的词权重稀疏字典（key=词字符串, value=激活权重）
        ES 用法：「rank_features」类型字段 + rank_features query，实现精确词汇信号加权检索。
        @param texts: 单条或批量文本
        @param top_k: 保留权重最高的 top_k 个词（默认64，减少 ES 存储体积）
        @return: List[Dict[str, float]] — ES rank_features 兼容格式
        """
        if isinstance(texts, str):
            texts = [texts]
        self.ensure_embedding_loaded()
        if self.model is None:
            return [{} for _ in texts]

        inputs = self.tokenizer(
            texts, padding=True, truncation=True,
            max_length=self.max_len, return_tensors="np"
        )
        model_input_names = [i.name for i in self.model.get_inputs()]
        input_feed = {k: v.astype(np.int64) for k, v in inputs.items() if k in model_input_names}

        try:
            # 复用 encode_colbert 的 ONNX 推理路径：last_hidden_state 已包含全量 token 向量
            outputs = self.model.run(["last_hidden_state"], input_feed)
            hidden = outputs[0]                    # [batch, seq_len, 1024]
            attention_mask = inputs["attention_mask"]  # [batch, seq_len]
            input_ids = inputs["input_ids"]            # [batch, seq_len]

            # [P0 批量 decode 优化] 提前计算特殊 token id 集合（避免在内层循环反复查询）。
            # 替代原来每 token 调用 tokenizer.decode([tid], skip_special_tokens=True) 的方案，
            # 改为 convert_ids_to_tokens 单次批量 FFI 调用 + 集合成员检测过滤特殊 token。
            _special_ids_set = set(self.tokenizer.all_special_ids)
            results = []
            for i in range(len(texts)):
                valid_mask = attention_mask[i].astype(bool)   # [seq_len]
                valid_hidden = hidden[i][valid_mask]           # [valid_len, 1024]
                valid_ids = input_ids[i][valid_mask]           # [valid_len]

                # [Fix-C] L2 Norm 近似稀疏权重（BGE-M3 ONNX 不暴露 vocab_projection layer）
                # 已知缺陷：L2 norm 永远 >= 0，所有 token 都有正权重，无法淘汰无关词（稀疏性失效）。
                # 短期降级策略：
                #   1. 归一化后引入相对阈值（top 50% 以上才保留），近似模拟 ReLU 后的稀疏效果。
                #   2. wSparse 权重已在 Java RrfFusionStep 端降低（configBm25 * 0.20），
                #      承认此信号为"BM25 补强"而非独立语义通道。
                # 长期修复：接入 BGE-M3 HuggingFace 原生推理获取真正的 sparse_weights（需换 runtime）。
                raw_weights = np.linalg.norm(valid_hidden, axis=-1)  # [valid_len]

                # 归一化：阈值过滤代替纯 min-max，高于中位数的 token 才参与稀疏检索
                # 目的：减少所有 token 均有正权重的问题，提升稀疏集中度（伪 RELU 效果）
                w_median = np.median(raw_weights)
                w_max = raw_weights.max()
                if w_max > w_median:
                    # 低于中位数的权重归零（稀疏化），高于中位数的归一化到 [0.1, 1.0]
                    weights = np.where(
                        raw_weights >= w_median,
                        0.1 + 0.9 * (raw_weights - w_median) / (w_max - w_median),
                        0.0  # 低于中位数：视为非激活词，稀疏化为零
                    )
                else:
                    weights = np.ones_like(raw_weights) * 0.5

                # 词聚合：同一词多次出现取最大权重（SPLADE Max 策略）
                # ── 写入时校验（第一道防线）────────────────────────────────────
                # 全量覆盖 ES rank_features 的两类合法性约束：
                #
                # [Key 约束] 所有非法字符由模块级常量 _RF_BANNED_CHARS 统一管理：
                #   - '.'   → ES 嵌套路径分隔符，直接导致 "failed to parse"
                #   - 空白  → 无语义 key，ES field-name 规范不允许
                #   - 控制字符 (0x00-0x1F / 0x7F) → ES 字段名注册拒绝
                #   注：上一版 Fix-E 的 frozenset 定义在内层循环体中，每个 token
                #   都重建一次对象，性能浪费；现提升至模块级常量，只构建一次。
                #
                # [Value 约束] 三类非法值：
                #   - w <= 0  → ES 严格要求正浮点数
                #   - NaN     → math.isfinite() 拦截；且 nan > 0 = False 也可兜底
                #   - +Inf    → 「inf > 0 = True」是现有过滤器的漏洞！
                #              json.dumps({"k": float("inf")}) 会抛 ValueError，
                #              导致整条 bulk action 在 Python 序列化层崩溃（不是 ES 错误）。
                #              必须在入队前用 math.isfinite() 显式拦截。
                # [P0 批量 decode] 单次 FFI 调用替代逐 token tokenizer.decode([tid])。
                # 根因：原代码每个 token 独立调用 decode([single_id])，触发约 seq_len 次
                #       Python→Rust 跨语言调用，在 GIL 下串行执行，累积约 50~120ms。
                # 修复：convert_ids_to_tokens(all_ids) 单次批量转换，返回含 ▁ 前缀的原始
                #       SentencePiece token 字符串列表；手动去除 ▁（U+2581）并过滤特殊 token。
                _valid_ids_list = valid_ids.tolist()
                _all_token_strs = self.tokenizer.convert_ids_to_tokens(_valid_ids_list)
                token_weights: dict = {}
                for tid, raw_tok, w in zip(_valid_ids_list, _all_token_strs, weights.tolist()):
                    # 权重值合法性（三合一：非 NaN、非 Inf、严格正数）
                    if not math.isfinite(w) or w <= 0:
                        continue
                    # 跳过 [CLS]/[SEP]/[PAD]/[UNK] 等特殊 token（替代 decode skip_special_tokens）
                    if tid in _special_ids_set:
                        continue
                    # 去除 SentencePiece 词首空格前缀 ▁（U+2581），还原干净 token 字符串
                    token_str = (raw_tok or "").lstrip("\u2581").strip()
                    if not token_str:
                        continue
                    # [ES 合法性] _RF_BANNED_CHARS 覆盖所有 ES rank_features key 非法字符
                    if any(c in _RF_BANNED_CHARS for c in token_str):
                        continue
                    # [语义有效性] 过滤纯标点/符号 token（无词汇检索价值）
                    # 根因：L2 Norm 近似无法像真实 SPLADE 那样将标点权重归零，
                    #   `。`/`，`/`—`/`/` 等符号因高频而获得虚高的 L2 Norm，
                    #   污染稀疏向量并挤占有意义词汇的 top_k 名额。
                    # 修复：用 Unicode category 做通用语言无关判断，
                    #   token 至少含 1 个字母(L*)或数字(N*)才允许进入稀疏向量。
                    if not _has_lexical_content(token_str):
                        continue
                    # 同词取最大权重（SPLADE Max）
                    if token_str not in token_weights or w > token_weights[token_str]:
                        token_weights[token_str] = w

                # 保留权重最高的 top_k 个词，降低 ES rank_features 索引体积
                if len(token_weights) > top_k:
                    sorted_items = sorted(token_weights.items(), key=lambda x: x[1], reverse=True)
                    token_weights = dict(sorted_items[:top_k])

                # ── 最终写入时校验（第二道防线 / 双保险）──────────────────────
                # 与入队校验完全对称，防止上方逻辑未来变动时漏网。
                # 使用相同的 _RF_BANNED_CHARS 和 math.isfinite 保证一致性。
                # rank_features 字段说明:
                #   https://www.elastic.co/guide/en/elasticsearch/reference/current/rank-features.html
                results.append({
                    k: round(float(v), 4)
                    for k, v in token_weights.items()
                    if math.isfinite(v) and v > 0
                    and not any(c in _RF_BANNED_CHARS for c in k)
                })

            return results

        except Exception as e:
            print(f"⚠️ [Sparse] encode_sparse failed: {e}")
            import traceback
            traceback.print_exc()
            return [{} for _ in texts]

    def encode_dual(self, texts: Union[List[str], str], top_k: int = 64) -> tuple:
        """
        业务功能：单次 ONNX 前向推理同时输出 dense 稠密向量 + sparse 稀疏向量。
        核心原理：
          BGE-M3 的 ONNX 图在一次 forward pass 中已同时计算出：
            ① sentence_embedding / outputs[0]：CLS token 池化后的 1024 维稠密向量
            ② last_hidden_state：全量 token 的 [batch, seq_len, 1024] 张量（用于稀疏权重）
          原来 encode() + encode_sparse() 两次调用 = 两次完整 forward pass，浪费约 50% GPU 推理时间。
          本方法一次 model.run() 取走全部输出，再分别提取 dense/sparse，节省一整次推理开销。
        关键流程：
          tokenize → model.run(all_outputs) → 提取 CLS 向量做 L2 归一化 → dense_vecs
                                            → 提取 last_hidden_state 做 L2 Norm 稀疏化 → sparse_vecs
        降级策略：任何异常时自动回退到 encode() + encode_sparse() 两次独立调用，保证正确性。

        @param texts:  单条或批量文本
        @param top_k:  稀疏向量保留 top_k 个词（与 encode_sparse 保持一致）
        @return:       (dense_vecs: List[List[float]], sparse_vecs: List[dict])
        """
        if isinstance(texts, str):
            texts = [texts]
        self.ensure_embedding_loaded()
        if self.model is None:
            return [], [{} for _ in texts]

        inputs = self.tokenizer(
            texts, padding=True, truncation=True,
            max_length=self.max_len, return_tensors="np"
        )
        model_input_names = [i.name for i in self.model.get_inputs()]
        input_feed = {k: v.astype(np.int64) for k, v in inputs.items() if k in model_input_names}

        try:
            # ── 单次 ONNX 推理，取全部输出（dense + sparse 同时获取）──────────────
            all_output_names = [o.name for o in self.model.get_outputs()]
            raw_outputs = self.model.run(all_output_names, input_feed)
            output_map = {name: tensor for name, tensor in zip(all_output_names, raw_outputs)}

            # ── ① Dense 稠密向量：取 CLS token（与 encode() 完全一致）─────────────
            feat = raw_outputs[0]   # 第0个输出：sentence_embedding 或 last_hidden_state
            embeddings = feat[:, 0, :] if feat.ndim == 3 else feat   # [batch, 1024]
            norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
            dense_vecs = (embeddings / (norms + 1e-9)).tolist()

            # 单条文本命中缓存写回
            if len(texts) == 1:
                if len(self._embedding_cache) > 1000:
                    self._embedding_cache.clear()
                self._embedding_cache[texts[0]] = dense_vecs[0]

            # ── ② Sparse 稀疏向量：与 encode_sparse() 完全相同的提取逻辑 ──────────
            # 优先取命名输出 last_hidden_state；若模型无此命名则尝试第0个（兜底）
            hidden = output_map.get("last_hidden_state", raw_outputs[0])  # [batch, seq_len, 1024]

            # 若模型只暴露 2D 输出（CLS-only），无法计算稀疏向量，降级为空字典
            if hidden.ndim < 3:
                print("⚠️ [DualEncode] last_hidden_state 维度不足，sparse 降级为空字典")
                return dense_vecs, [{} for _ in texts]

            attention_mask = inputs["attention_mask"]
            input_ids      = inputs["input_ids"]
            # [P0 批量 decode 优化] 与 encode_sparse 完全对称，提前计算特殊 token id 集合
            _special_ids_set = set(self.tokenizer.all_special_ids)
            sparse_results = []

            for i in range(len(texts)):
                valid_mask   = attention_mask[i].astype(bool)
                valid_hidden = hidden[i][valid_mask]         # [valid_len, 1024]
                valid_ids    = input_ids[i][valid_mask]      # [valid_len]

                raw_weights = np.linalg.norm(valid_hidden, axis=-1)
                w_median = np.median(raw_weights)
                w_max    = raw_weights.max()
                if w_max > w_median:
                    weights = np.where(
                        raw_weights >= w_median,
                        0.1 + 0.9 * (raw_weights - w_median) / (w_max - w_median),
                        0.0
                    )
                else:
                    weights = np.ones_like(raw_weights) * 0.5

                # [P0 批量 decode] 与 encode_sparse 一致，单次 convert_ids_to_tokens 替代逐 token decode
                _valid_ids_list = valid_ids.tolist()
                _all_token_strs = self.tokenizer.convert_ids_to_tokens(_valid_ids_list)
                token_weights: dict = {}
                for tid, raw_tok, w in zip(_valid_ids_list, _all_token_strs, weights.tolist()):
                    if not math.isfinite(w) or w <= 0:
                        continue
                    # 跳过特殊 token（[CLS]/[SEP]/[PAD]/[UNK]）
                    if tid in _special_ids_set:
                        continue
                    # 去除 SentencePiece ▁ 词首空格前缀（U+2581）
                    token_str = (raw_tok or "").lstrip("\u2581").strip()
                    if not token_str:
                        continue
                    if any(c in _RF_BANNED_CHARS for c in token_str):
                        continue
                    if not _has_lexical_content(token_str):
                        continue
                    if token_str not in token_weights or w > token_weights[token_str]:
                        token_weights[token_str] = w

                if len(token_weights) > top_k:
                    sorted_items = sorted(token_weights.items(), key=lambda x: x[1], reverse=True)
                    token_weights = dict(sorted_items[:top_k])

                sparse_results.append({
                    k: round(float(v), 4)
                    for k, v in token_weights.items()
                    if math.isfinite(v) and v > 0
                    and not any(c in _RF_BANNED_CHARS for c in k)
                })

            return dense_vecs, sparse_results

        except Exception as e:
            # 降级：任何异常回退到两次独立调用，保证入库流程不中断
            print(f"⚠️ [DualEncode] encode_dual 失败，降级为两次独立调用: {e}")
            dense  = self.encode(texts)
            sparse = self.encode_sparse(texts, top_k=top_k)
            return dense, sparse



    def rerank(self, query: str, documents: List[str]) -> List[float]:
        """对候选文档进行重排打分"""
        if not documents:
            return []
        self.ensure_reranker_loaded()
        if self.reranker is None: return []

        import hashlib
        doc_hash = hashlib.md5("".join(documents).encode('utf-8')).hexdigest()
        cache_key = f"{query}_{doc_hash}"
        if cache_key in self._rerank_cache: return self._rerank_cache[cache_key]

        pairs = [[query, doc] for doc in documents]
        # 使用独立的 rerank_max_len=256，Attention O(256²) vs O(512²) 快 4x
        _rerank_max = getattr(self, 'rerank_max_len', 256)
        inputs = self.rerank_tokenizer(pairs, padding=True, truncation=True, max_length=_rerank_max, return_tensors="np")
        
        model_input_names = [i.name for i in self.reranker.get_inputs()]
        input_feed = {}
        for k in model_input_names:
            if k in inputs:
                val = inputs[k].astype(np.int64)
                if val.ndim == 3 and val.shape[1] == 1:
                    val = np.squeeze(val, axis=1)
                input_feed[k] = val
        
        try:
            outputs = self.reranker.run(None, input_feed)
            logits = outputs[0]
        except Exception as e:
            is_dml = "DML" in getattr(self, "device", "CPU")
            print(f"❌ Reranker Run Failed: {e}")
            if is_dml:
                print("🔄 [Fallback] GPU Execution Failed. Retrying on CPU for stability...")
                try:
                    if hasattr(self, 'cpu_reranker') and self.cpu_reranker is not None:
                        outputs = self.cpu_reranker.run(None, input_feed)
                        logits = outputs[0]
                    else:
                        r_path = os.path.normpath(str(self.reranker_path.absolute()))
                        cpu_sess = InferenceSession(r_path, providers=['CPUExecutionProvider'])
                        outputs = cpu_sess.run(None, input_feed)
                        logits = outputs[0]
                except Exception as ex:
                    print(f"❌ [Fallback Failed] CPU retry also failed: {ex}")
                    return [0.0] * len(documents)
            else:
                return [0.0] * len(documents)
        
        if logits.ndim == 1:
            scores = logits.tolist()
        else:
            scores = logits[:, 0].tolist() if logits.shape[1] > 0 else logits.flatten().tolist()

        if len(self._rerank_cache) > 500: self._rerank_cache.clear()
        self._rerank_cache[cache_key] = scores
        return scores

    def get_health_status(self) -> dict:
        """获取模型实时运行状态"""
        dev = getattr(self, "device", "CPU")
        embedding_loaded = getattr(self, "model", None) is not None
        reranker_loaded = getattr(self, "reranker", None) is not None
        status = {
            "model_loaded": embedding_loaded,
            "embedding_loaded": embedding_loaded,
            "reranker_loaded": reranker_loaded,
            "acceleration": dev.upper(),
            "device": dev,
            "providers": self.model.get_providers() if getattr(self, "model", None) else [],
            "reranker_providers": self.reranker.get_providers() if getattr(self, "reranker", None) else [],
            "last_used_at": getattr(self, "last_used_at", 0.0),
            "idle_seconds": self.get_idle_seconds(),
            "idle_unload_enabled": os.getenv("AI_MODEL_IDLE_UNLOAD", "true").lower() == "true",
        }
        return status

    def update_config(self, cfg: dict):
        """配置热重载"""
        self.cfg = cfg
        print(f"Applying new config: {cfg}")
        if "device" in cfg:
            self.device = cfg["device"]
            print(f"🔄 Device property dynamically updated to: {self.device}")
        if "cpuThreads" in cfg:
            os.environ["AI_CPU_THREADS"] = str(cfg["cpuThreads"])
        if cfg.get("reload") or "cpuThreads" in cfg:
            self.load_model()

# 全局单例
model_manager = ModelManager()
