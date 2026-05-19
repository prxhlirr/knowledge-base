"""
业务功能（Phase 2 实现）：PaddleOCR PP-Structure 全图像版面分析管道。
触发条件（Phase 2 激活）：parse_options["scanned"] = True（由 ScannedDetectorTransform 写入）

Phase 2 实现流程（接收 DeskewTransform 旋转矫正后的 PDF）：
  1. [懒加载] 首次调用时初始化 PPStructure 实例（延迟 import 避免普通路径启动受影响）
  2. fitz（PyMuPDF）逐页渲染为 300 DPI RGB 图像（numpy array）
  3. PPStructure 版面分析，获取区域列表（含 type / bbox / res）
  4. 按区域类型分流处理：
       text/title  → 提取 OCR 文字，title 加 "## " Markdown 前缀
       table       → 解析 HTML → Markdown 表格（thead + separator + tbody）
       figure / figure_caption → 直接丢弃（圆形公章 / 底纹背景图像）
  5. 区域按 bbox y 坐标升序排列，保证版面阅读顺序
  6. 页间插入空行，全文拼接写入临时 _ocr_result.txt
  7. 返回临时 .txt 文件路径（后续由 ScannedPdfParser 接管）

GPU 策略（与现有 onnxruntime-gpu 保持一致）：
  - 使用 paddlepaddle-gpu（CUDA 12.x）
  - 显存限制：FLAGS_fraction_of_gpu_memory_to_use=0.3（最多占用 30% VRAM，与 BGE-M3 共存）
  - 单文档流水线内：OCR（解析阶段）与向量化（写入阶段）严格串行，无显存竞争

离线部署：
  - 必须提前将模型放入 PADDLE_OCR_MODEL_DIR
  - 初始化时强制传入所有模型目录绝对路径，禁止自动下载

Phase 2 依赖（requirements.txt 已追加）：
  - paddlepaddle-gpu==2.6.1.post120（CUDA 12.x）
  - paddleocr（PP-Structure + 版面分析）
  - opencv-python-headless（fitz pixmap → numpy 转换辅助）
"""

import os
import re
import tempfile
import json
import time
import math
import gc

from core.parsing.document_element import PageParseStatus, ParseReport

# ── GPU 显存限制与 IR 优化屏蔽（在 import paddle 之前设置生效）─────────────────────
# 1. 最多使用 30% GPU 显存，避免与 BGE-M3 onnxruntime-gpu 产生 OOM 竞争
os.environ.setdefault("FLAGS_fraction_of_gpu_memory_to_use", "0.3")
# 2. [物理级防爆金刚罩] 彻底禁用 C++ 层的 Graph IR 融合优化，杜绝 Illegal Instruction 崩溃
# 根因：Paddle 默认 IR 优化阶段（如 SelfAttentionFusePass）会调用物理/虚拟 CPU 缺失的 AVX 高维向量指令
os.environ.setdefault("FLAGS_enable_ir_optim", "0")

# ── 模块级 GPU 探测缓存 ─────────────────────────────────────────────────────
# 仅在模块首次导入时探测一次，后续直接读缓存，零性能损耗
_USE_GPU: bool = False          # 默认 CPU，探测成功后更新为 True
_GPU_PROBED: bool = False       # 探测是否已执行过


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _require_model_dirs(model_dir: str, required_dirs: dict, strict: bool, engine_name: str) -> dict:
    """
    离线模型目录校验。
    strict=true 时缺模型直接失败，避免 PaddleOCR 回退默认路径并尝试联网下载。
    """
    missing = {
        name: path
        for name, path in required_dirs.items()
        if not os.path.isdir(path)
    }
    if missing and strict:
        detail = "\n".join(f"  - {name}: {path}" for name, path in missing.items())
        raise RuntimeError(
            f"[OcrPipeline] {engine_name} 离线模型不完整，已阻止 PaddleOCR 自动联网下载。\n"
            f"模型根目录: {model_dir}\n"
            f"缺失目录:\n{detail}\n"
            "请补齐模型后重启容器，或仅在在线开发环境将 PADDLE_OCR_OFFLINE_STRICT=false。"
        )
    for name, path in missing.items():
        print(f"⚠️ [OcrPipeline] {engine_name} 模型目录缺失，将回退 PaddleOCR 默认路径: {name}={path}")
    return {
        name: path
        for name, path in required_dirs.items()
        if os.path.isdir(path)
    }


def _detect_paddle_gpu() -> bool:
    """
    业务功能：探测当前运行环境是否具备 PaddlePaddle CNN 推理所需的完整 GPU 依赖。
    关键流程：
      1. 检查 paddle 是否编译含 CUDA 支持
      2. 检查系统 CUDA 设备数量
      3. 检查 cuBLAS（libcublasLt.so.12）是否可加载 ← 关键！
         根因：PPStructure 的卷积网络（FusedConv2dAddActKernel）依赖 cuBLAS
               若 cuBLAS 缺失，Paddle 在推理时会 SIGABRT 直接杀死进程（PID 1 崩溃）
               而非抛出 Python 异常，因此必须在初始化前提前检测
      4. 执行轻量 GPU 张量运算验证 cuDNN 完整性
      5. 结果缓存为模块级常量，仅探测一次
    """
    global _USE_GPU, _GPU_PROBED
    if _GPU_PROBED:
        return _USE_GPU
    _GPU_PROBED = True

    try:
        import paddle
        # Step1：编译层检查
        if not paddle.is_compiled_with_cuda():
            print("[OcrPipeline] GPU 探测: paddle 未编译 CUDA 支持 → 使用 CPU")
            _USE_GPU = False
            return False

        # Step2：驱动层检查
        if paddle.device.cuda.device_count() == 0:
            print("[OcrPipeline] GPU 探测: 未检测到 CUDA 设备 → 使用 CPU")
            _USE_GPU = False
            return False

        # Step3：cuBLAS 可用性检查（Paddle 卷积推理的硬性依赖）
        # 注意：Paddle 使用 dlopen() 动态加载，不抛 Python 异常，缺失时 SIGABRT 杀进程
        # 只检查 Paddle 硬编码的系统路径，LD_LIBRARY_PATH 不够用
        import ctypes
        _cublas_candidates = [
            "/usr/local/cuda/lib64/libcublasLt.so.12",         # Paddle 硬编码路径
            "/usr/local/cuda-12.4/targets/x86_64-linux/lib/libcublasLt.so.12",  # bind-mount 路径
        ]
        _cublas_ok = False
        for _path in _cublas_candidates:
            if os.path.isfile(_path):
                try:
                    ctypes.CDLL(_path)
                    _cublas_ok = True
                    break
                except OSError:
                    pass
        if not _cublas_ok:
            print("[OcrPipeline] GPU 探测: libcublasLt.so.12 不可用 → 使用 CPU"
                  "（cuBLAS 缺失会导致 PPStructure 推理时 SIGABRT 崩溃整个进程）")
            _USE_GPU = False
            return False

        # Step4：设置设备索引并执行轻量张量运算，验证 cuDNN 完整性
        _ocr_gpu_id = int(os.getenv("OCR_GPU_ID", "0"))
        paddle.set_device(f"gpu:{_ocr_gpu_id}")
        _t = paddle.zeros([1], dtype="float32")
        _ = (_t + 1.0).numpy()

        _USE_GPU = True
        print(f"[OcrPipeline] GPU 探测: 完整 CUDA 环境可用（{paddle.device.cuda.device_count()} 设备，当前使用 gpu:{_ocr_gpu_id}）→ 使用 GPU")
        return True

    except Exception as e:
        _USE_GPU = False
        print(f"[OcrPipeline] GPU 探测: 运行时验证失败 → 降级 CPU（原因: {e}）")
        return False


# ── 模块级 PPStructure 单例：避免每次处理文档都重新初始化（启动耗时 30~60s）──
_structure_engine = None
# ── 模块级 纯OCR穿透引擎单例：用于在Layout模型感知失明时强制扫网兜底 ──
_pure_ocr_engine = None


def _get_engine():
    """
    懒加载并缓存 PPStructure 引擎单例。
    首次调用时初始化，后续调用直接复用，消除重复加载模型的启动成本。
    """
    global _structure_engine
    if _structure_engine is not None:
        return _structure_engine

    # ── [物理级补丁] 彻底解决 Numpy 2.0+ 导致 imgaug 找不到 sctypes 崩溃的全局冲突 ──
    import numpy as np
    if not hasattr(np, "sctypes"):
        np.sctypes = {
            'int': [np.int8, np.int16, np.int32, np.int64],
            'uint': [np.uint8, np.uint16, np.uint32, np.uint64],
            'float': [np.float16, np.float32, np.float64],
            'complex': [np.complex64, np.complex128],
            'others': [bool, object, str, bytes]
        }

    # ── [物理级补丁] 优先导入 pyclipper 规避后续解压时引发的 zlib 不一致流错误 (Error -2) ──
    try:
        import pyclipper
    except ImportError:
        pass

    # ── [第一性原理物理防爆熔断器] 动态劫持并重写底层 paddle.inference.Config 以根除非法指令崩溃 ──
    try:
        import sys
        # 提前强制引入底层推理配置层（由于该模块当前为懒加载，此处为最早时间窗口）
        import paddle.inference as paddle_infer

        # 保存原始 C++ 绑定类符号句柄
        _OriginalConfig = paddle_infer.Config

        class _SafePaddleConfig(_OriginalConfig):
            """
            业务功能：从 Python 解释器反射层彻底锁死 PaddlePaddle C++ 推理器的 IR 优化与 MKLDNN 加速。
            根因：在特定物理机/虚拟机 CPU 环境下，SelfAttentionFusePass 包含物理 CPU 不支持的高维机器码指令，
                  执行时触发 SIGILL 崩溃。本代理类剥夺了上层模块对此控制状态的写权限。
            """
            def __init__(self, *args, **kwargs):
                # 1. 驱动底层 C++ AnalysisConfig 完成内存分配与字段填充
                super().__init__(*args, **kwargs)
                
                # 2. [核心阻断] C++ 实例落地的第一时刻强行灌入 False，切断底层进入 FusePass 分支的程序流
                self.switch_ir_optim(False)
                
                # 3. 特征爆破：定点清除出事的 self_attention_fuse_pass（双保险）
                try:
                    self.delete_pass("self_attention_fuse_pass")
                except Exception:
                    pass

                # 4. 物理屏蔽 MKLDNN 加速以绝后患，确保最稳健的通用 CPU 指令路径
                try:
                    self.disable_mkldnn()
                except Exception:
                    pass

            def switch_ir_optim(self, x: bool = True):
                """方法焊死：覆写上层试图调用的 switch_ir_optim，强制锁死为 False"""
                super().switch_ir_optim(False)

            def enable_mkldnn(self):
                """方法焊死：强制屏蔽任何显式开启 mkldnn 的行为"""
                pass

        # 修改 Python Module 属性字典引用，迫使后续 PPStructure 内的所有子模型实例化均重定向至此安全类
        paddle_infer.Config = _SafePaddleConfig
        
        # 兜底防御：确保 paddle.inference.Config 原始引用同步被劫持
        import paddle
        if hasattr(paddle, 'inference'):
            paddle.inference.Config = _SafePaddleConfig
            
        print("[OcrPipeline] 🛡️ [物理防爆熔断器] 已重写底层 paddle.inference.Config。IR 优化与 MKLDNN 彻底隔离封锁！")
    except Exception as patch_err:
        print(f"⚠️ [OcrPipeline] 防爆熔断补丁植入失败（可能底层接口发生变动）: {patch_err}")

    from paddleocr import PPStructure

    # 从环境变量读取离线模型目录（离线部署必须设置，避免自动下载）
    model_dir = os.getenv("PADDLE_OCR_MODEL_DIR", "/app/models/paddle_ocr")

    # 各子模型路径（与推荐离线目录结构对齐）
    det_dir    = os.path.join(model_dir, "ch_PP-OCRv4_det_infer")
    rec_dir    = os.path.join(model_dir, "ch_PP-OCRv4_rec_infer")
    cls_dir    = os.path.join(model_dir, "ch_ppocr_mobile_v2.0_cls_infer")
    table_dir  = os.path.join(model_dir, "ch_ppstructure_mobile_v2.0_SLANet_infer")
    layout_dir = os.path.join(model_dir, "picodet_lcnet_x1_0_fgd_layout_infer")
    strict_offline = _env_bool("PADDLE_OCR_OFFLINE_STRICT", False)
    model_paths = _require_model_dirs(
        model_dir,
        {
            "det_model_dir": det_dir,
            "rec_model_dir": rec_dir,
            "cls_model_dir": cls_dir,
            "table_model_dir": table_dir,
            "layout_model_dir": layout_dir,
        },
        strict_offline,
        "PPStructure",
    )

    # ── PaddleOCR 推理设备策略 ────────────────────────────────────────────────
    # 结论：固定 CPU 模式，不走 GPU 路径。
    # 根因分析：
    #   当前容器 GPU 依赖链不完整——cuBLAS (libcublasLt.so.12) 未在 Paddle 期望的系统路径
    #   Paddle 的卷积算子（FusedConv2dAddActKernel）若找不到 cuBLAS，不抛 Python 异常，
    #   而是直接 SIGABRT 杀死整个进程（PID 1 = uvicorn），导致服务完全崩溃。
    # 决策理由：
    #   1. OCR 是低频辅助路径（仅扫描件触发），CPU 速度（2~5s/页）满足业务需求
    #   2. 性能关键路径（向量化/重排序）已由 ONNX Runtime + GPU 覆盖
    #   3. 进程稳定性 >> OCR 推理速度，不能因 OCR 拖垮整个服务
    # 若未来需要 GPU OCR，需确保 nvidia-cublas-cu12 pip 包已安装并建立软链接
    use_gpu = _detect_paddle_gpu()  # 保留探测逻辑，输出诊断日志，但结果不影响下方配置

    init_kwargs = {
        "lang":            "ch",          # 中文识别优化
        # use_angle_cls=False：cls 模型未下载时 PaddleOCR 内部调用 sys.exit() 导致进程崩溃
        "use_angle_cls":   False,
        "table":           True,          # 启用表格识别
        "ocr":             True,          # 文字区域 OCR
        "show_log":        False,         # 关闭冗余日志
        # 策略：如果环境变量强制设为 True，则即便探测失败也尝试开启（可能用户已手动处理库路径）
        # 否则默认遵循 _detect_paddle_gpu 的安全探测结果。
        "use_gpu":         os.getenv("OCR_USE_GPU", str(use_gpu)).lower() == "true",
        "gpu_id":          int(os.getenv("OCR_GPU_ID", "0")),
        "ir_optim":        False,         # ── 双重物理防爆保险：显式关闭内部算子 IR 优化 ──
    }

    init_kwargs.update(model_paths)

    print(f"[OcrPipeline] 初始化 PPStructure（模型目录: {model_dir}）...")
    _structure_engine = PPStructure(**init_kwargs)
    print("[OcrPipeline] ✅ PPStructure 初始化完成")

    return _structure_engine


def _get_pure_ocr_engine():
    """
    业务功能：懒加载并缓存纯粹的 PaddleOCR 识别器单例（不包含版面分析模块）。
    使用场景：当终极熔断保险丝执行时，若 PPStructure 由于版面错判导致输出为空，
             则在此处激活最后的防线，强制使用纯粹的文本定位与字符识别（DBNet/SVTR）召回文字。
    物理开销：与 PPStructure 物理复用一套 det/rec 权重文件，轻量快速。
    """
    global _pure_ocr_engine
    if _pure_ocr_engine is not None:
        return _pure_ocr_engine

    from paddleocr import PaddleOCR

    model_dir = os.getenv("PADDLE_OCR_MODEL_DIR", "/app/models/paddle_ocr")
    det_dir    = os.path.join(model_dir, "ch_PP-OCRv4_det_infer")
    rec_dir    = os.path.join(model_dir, "ch_PP-OCRv4_rec_infer")
    cls_dir    = os.path.join(model_dir, "ch_ppocr_mobile_v2.0_cls_infer")
    strict_offline = _env_bool("PADDLE_OCR_OFFLINE_STRICT", False)
    model_paths = _require_model_dirs(
        model_dir,
        {
            "det_model_dir": det_dir,
            "rec_model_dir": rec_dir,
            "cls_model_dir": cls_dir,
        },
        strict_offline,
        "纯 OCR",
    )

    use_gpu = _detect_paddle_gpu()

    init_kwargs = {
        "lang":            "ch",
        "use_angle_cls":   False,
        "show_log":        False,
        "use_gpu":         os.getenv("OCR_USE_GPU", str(use_gpu)).lower() == "true",
        "gpu_id":          int(os.getenv("OCR_GPU_ID", "0")),
        "ir_optim":        False,
    }

    init_kwargs.update(model_paths)

    print(f"[OcrPipeline] 初始化纯 OCR 穿透引擎...")
    _pure_ocr_engine = PaddleOCR(**init_kwargs)
    print("[OcrPipeline] ✅ 纯 OCR 穿透引擎初始化完成")

    return _pure_ocr_engine


def _extract_region_text(res) -> str:
    """
    业务功能：从 PP-Structure 文字/标题区域的 res 字段中提取纯文本。
    关键流程：
      PP-Structure res 格式因版本不同有两种形态：
        形式A（v2.6+）: [{"text": "...", "confidence": 0.95}, ...]
        形式B（旧版）:  [([[x1,y1,...], ("text", 0.95)]), ...]  — OCR 原始格式
      本函数兼容两种格式，优先使用 dict["text"]，回退用 tuple[1][0]。
    """
    if not res:
        return ""

    lines = []

    if isinstance(res, list):
        for item in res:
            if isinstance(item, dict):
                # 形式A：{"text": "...", "confidence": 0.95}
                text = item.get("text", "").strip()
                if text:
                    lines.append(text)
            elif isinstance(item, (list, tuple)) and len(item) == 2:
                # 形式B：([bbox], ("text", confidence))
                content = item[1]
                if isinstance(content, (list, tuple)) and len(content) >= 1:
                    text = str(content[0]).strip()
                    if text:
                        lines.append(text)
                elif isinstance(content, str):
                    lines.append(content.strip())
    elif isinstance(res, dict):
        # 单结果 dict 格式兜底
        text = res.get("text", "").strip()
        if text:
            lines.append(text)

    return "\n".join(filter(None, lines))


def _flatten_pure_ocr_result(result) -> list:
    """
    Normalize PaddleOCR.ocr() results across PaddleOCR versions.

    Expected variants include:
      [[bbox, (text, score)], ...]
      [[[bbox, (text, score)], ...]]
      [{"text": "...", "bbox": [...]}, ...]
    """
    items = []

    def visit(node):
        if not node:
            return
        if isinstance(node, dict):
            text = str(node.get("text", "")).strip()
            bbox = node.get("bbox") or node.get("points")
            if text:
                items.append((bbox, text))
            return
        if isinstance(node, (list, tuple)):
            if len(node) >= 2 and isinstance(node[1], (list, tuple)):
                content = node[1]
                if content and isinstance(content[0], str):
                    items.append((node[0], content[0].strip()))
                    return
            for child in node:
                visit(child)

    visit(result)
    return [(bbox, text) for bbox, text in items if text]


def _bbox_from_points(points) -> list:
    """Convert PaddleOCR polygon points to [x1, y1, x2, y2]."""
    try:
        if not points:
            return [0, 0, 0, 0]
        if len(points) == 4 and all(isinstance(v, (int, float)) for v in points):
            return [float(v) for v in points]
        xs = [float(p[0]) for p in points if isinstance(p, (list, tuple)) and len(p) >= 2]
        ys = [float(p[1]) for p in points if isinstance(p, (list, tuple)) and len(p) >= 2]
        if xs and ys:
            return [min(xs), min(ys), max(xs), max(ys)]
    except Exception:
        pass
    return [0, 0, 0, 0]


def _html_table_to_markdown(html: str) -> str:
    """
    业务功能：将 PP-Structure 输出的表格 HTML 转换为 Markdown 格式。
    关键流程：
      1. 正则提取所有 <tr> 行
      2. 每行内提取 <td>/<th> 单元格内容（去除内部 HTML 标签）
      3. 【防伪盾牌】：如果表格只有 1 行 或 1 列，判定为伪表格，直接平铺文本。
      4. 正常表格渲染为 Markdown 格式（thead + separator + tbody）
    使用标准库 re 避免引入额外依赖（不依赖 BeautifulSoup）。
    """
    if not html:
        return ""

    # 提取所有 <tr> 块（DOTALL 允许跨行匹配）
    row_matches = re.findall(r"<tr[^>]*>(.*?)</tr>", html, re.DOTALL | re.IGNORECASE)
    if not row_matches:
        return ""

    table_rows = []
    for row_html in row_matches:
        # 提取单元格（td 和 th 均视为单元格）
        cells = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", row_html, re.DOTALL | re.IGNORECASE)
        # 去除单元格内的 HTML 标签，压缩空白
        cells = [re.sub(r"<[^>]+>", "", c).strip() for c in cells]
        cells = [re.sub(r"\s+", " ", c) for c in cells]
        if cells:
            table_rows.append(cells)

    if not table_rows:
        return ""

    # 获取行列维度
    row_count = len(table_rows)
    max_cols  = max(len(row) for row in table_rows)

    # ── [物理防御] 伪表格降维（根治 | 的产生源头） ──
    # 如果表格只有 1 行（表头即内容）或只有 1 列（清单列表），
    # 渲染成 Markdown Table 无语义价值且易引发 Chunker 状态机劫持。
    # 动作：直接将内容以空格/换行平铺，坚决不带 | 符号。
    if row_count <= 1 or max_cols <= 1:
        _flat_lines = [" ".join(row) for row in table_rows]
        return "\n".join(_flat_lines).strip()

    # 对齐列数
    table_rows = [row + [""] * (max_cols - len(row)) for row in table_rows]

    header    = "| " + " | ".join(table_rows[0]) + " |"
    separator = "| " + " | ".join(["---"] * max_cols) + " |"
    body      = "\n".join("| " + " | ".join(row) + " |" for row in table_rows[1:])
    return "\n".join([header, separator, body])


class OcrPipelineTransform:
    """
    Phase 2 实现：PaddleOCR PP-Structure 政务扫描件全图像解析管道。
    触发条件：opts["scanned"] = True（由 ScannedDetectorTransform 判定后写入）
    核心策略：版面区域分类驱动的选择性 OCR，figure 区域（公章/图片）直接丢弃，
              不进入 TextCleaner 以节省无效处理循环。
    """

    # 丢弃的区域类型（公章/底纹/插图/图注）
    _DISCARD_TYPES = frozenset({"figure", "figure_caption"})
    # 保留并作为标题处理的区域类型
    _TITLE_TYPES   = frozenset({"title"})
    # 保留并作为表格处理的区域类型
    _TABLE_TYPES   = frozenset({"table"})
    # 其余（text / equation 等）均当普通文本处理
    # 每页渲染分辨率：离线环境默认更保守，防止大幅面 PDF 渲染后触发 Paddle 内存分配失败
    _RENDER_DPI = int(os.getenv("OCR_RENDER_DPI", "220"))
    _MIN_RENDER_DPI = int(os.getenv("OCR_MIN_RENDER_DPI", "150"))
    _MAX_RENDER_PIXELS = int(os.getenv("OCR_MAX_RENDER_PIXELS", "6000000"))
    # PP-Structure 版面分析低召回时，触发纯 OCR 穿透兜底的最小字数阈值
    _PURE_OCR_FALLBACK_MIN_CHARS = int(os.getenv("OCR_PAGE_FALLBACK_MIN_CHARS", "20"))

    def should_apply(self, opts: dict) -> bool:
        """仅对扫描件 PDF 执行（scanned=True 由 ScannedDetectorTransform 写入）"""
        return opts.get("scanned", False)

    def _page_dpi_scale(self, page) -> tuple[float, int]:
        """按页面物理尺寸动态降低渲染 DPI，避免超大页面一次性生成巨幅 RGB 图。"""
        width_pt = max(float(page.rect.width), 1.0)
        height_pt = max(float(page.rect.height), 1.0)
        base_dpi = max(self._RENDER_DPI, self._MIN_RENDER_DPI)
        base_pixels = (width_pt * base_dpi / 72.0) * (height_pt * base_dpi / 72.0)
        if base_pixels <= self._MAX_RENDER_PIXELS:
            return base_dpi / 72.0, base_dpi

        capped_dpi = int(math.sqrt(self._MAX_RENDER_PIXELS * 72.0 * 72.0 / (width_pt * height_pt)))
        capped_dpi = max(self._MIN_RENDER_DPI, min(base_dpi, capped_dpi))
        return capped_dpi / 72.0, capped_dpi

    @staticmethod
    def _is_memory_error(err: Exception) -> bool:
        msg = str(err).lower()
        return (
            "available memory" in msg
            or "requested bytes" in msg
            or "bad allocation" in msg
            or "out of memory" in msg
            or "std::bad_alloc" in msg
        )

    def apply(self, pdf_path: str, opts: dict) -> "str | None":
        """
        业务功能：对扫描件 PDF 执行 PP-Structure 版面分析 + OCR，输出 Markdown 格式文本文件。
        关键流程：
          1. 获取 PPStructure 单例引擎（首次调用时懒加载约30~60s）
          2. fitz 逐页渲染为 _RENDER_DPI DPI 的 numpy RGB 图像
          3. 每页调用 PPStructure 版面分析，按区域类型分流：
               figure/figure_caption → 丢弃（公章/图片）
               title                 → OCR文字 + ## 前缀
               text/其他             → OCR文字
               table                 → HTML → Markdown 表格
          4. 区域按 bbox[1] y坐标升序排列（保证版面阅读顺序）
          5. 全文拼接 → 写入临时 _ocr_result.txt → 返回路径
        降级策略：
          - PaddleOCR 未安装 → ImportError 捕获，返回 None（走 MarkItDown 兜底）
          - 任何处理异常 → Exception 捕获，返回 None（不阻断入库流程）

        :param pdf_path: 旋转矫正后的 PDF 路径（DeskewTransform 输出）
        :param opts:     共享选项字典（当前方法只读）
        :return:         临时 _ocr_result.txt 路径 | None（失败时降级）
        """
        try:
            import fitz
            import numpy as np

            engine = _get_engine()

            doc = fitz.open(pdf_path)
            # 在关闭 doc 前保存页数（fitz 关闭后访问 page_count 可能返回 0 或抛异常）
            page_count = doc.page_count
            all_page_contents = []
            all_elements = []
            page_statuses = []

            for page_idx, page in enumerate(doc):
                print(f"  [OcrPipeline] 处理第 {page_idx + 1}/{doc.page_count} 页...")
                page_started = time.time()
                parser_type = "OCR_STRUCTURE"

                # ── 方案 B：收紧容错网，将渲染与版面分析全面包裹，防单页图片崩坏波及全文 ──
                try:
                    dpi_scale, page_dpi = self._page_dpi_scale(page)
                    last_err = None
                    for attempt_dpi in [page_dpi, self._MIN_RENDER_DPI]:
                        try:
                            dpi_scale = attempt_dpi / 72.0
                            mat = fitz.Matrix(dpi_scale, dpi_scale)
                            # 渲染为 RGB pixmap → numpy array（shape: [H, W, 3]）
                            pix = page.get_pixmap(matrix=mat, colorspace=fitz.csRGB)
                            img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(
                                pix.height, pix.width, 3
                            )
                            pixel_count = int(pix.width) * int(pix.height)
                            print(
                                f"    [OcrPipeline] 渲染参数 dpi={attempt_dpi}, "
                                f"size={pix.width}x{pix.height}, pixels={pixel_count}"
                            )
                            # PP-Structure 版面分析
                            structure_result = engine(img)
                            break
                        except Exception as render_err:
                            last_err = render_err
                            try:
                                del img
                                del pix
                            except Exception:
                                pass
                            gc.collect()
                            if attempt_dpi <= self._MIN_RENDER_DPI or not self._is_memory_error(render_err):
                                raise
                            print(
                                f"    ⚠️ [OcrPipeline] 第 {page_idx + 1} 页 OCR 内存不足，"
                                f"降级到 {self._MIN_RENDER_DPI} DPI 重试: {render_err}"
                            )
                    else:
                        raise last_err or RuntimeError("OCR render failed")
                except Exception as page_err:
                    print(f"  ⚠️ [OcrPipeline] 第 {page_idx + 1} 页像素渲染或 PP-Structure 失败，安全跳过该页: {page_err}")
                    continue

                # 收集本页所有有效区域（y坐标, 文本内容, 类型, bbox）
                page_regions = []
                img_h, img_w = img.shape[:2]

                for region in (structure_result or []):
                    region_type = str(region.get("type", "")).lower().strip()
                    bbox        = region.get("bbox", [0, 0, 0, 0])
                    res         = region.get("res", {})
                    x1, y1, x2, y2 = bbox if len(bbox) == 4 else (0, 0, 0, 0)
                    y_pos       = y1

                    # ── 🏆 第一性原理：物理级抗噪隔离网 ──
                    #
                    # 1. [边缘批注/骑缝章屏蔽]：x坐标完全落在左右 4% 的护城河安全区内，判定为非正文干扰
                    if x2 < img_w * 0.04 or x1 > img_w * 0.96:
                        print(f"    [OcrPipeline] 物理屏蔽边缘干扰区域 (x={x1:.0f}-{x2:.0f}, type={region_type})")
                        continue

                    # 2. [首页红头物理湮灭]：第一页顶部 12% 区域内的任何文字区域（密级/单位名）不入分片正文
                    if page_idx == 0 and y2 < img_h * 0.12:
                        print(f"    [OcrPipeline] 物理湮灭首页红头区域 (y={y1:.0f}-{y2:.0f}, type={region_type})")
                        continue

                    # 丢弃公章/图片/图注区域（策略B核心）
                    if region_type in self._DISCARD_TYPES:
                        print(f"    [OcrPipeline] 丢弃 {region_type} 区域（y={y_pos:.0f}）")
                        continue

                    # 标题区域：OCR + Markdown 前缀
                    if region_type in self._TITLE_TYPES:
                        text = _extract_region_text(res)
                        if text.strip():
                            page_regions.append((y_pos, f"## {text.strip()}", "title", bbox))
                        continue

                    # 表格区域：HTML → Markdown
                    if region_type in self._TABLE_TYPES:
                        html_content = ""
                        if isinstance(res, dict):
                            html_content = res.get("html", "")
                        elif isinstance(res, list) and res:
                            # 部分版本表格 res 是列表，取第一个且含 html 的 dict
                            for item in res:
                                if isinstance(item, dict) and "html" in item:
                                    html_content = item["html"]
                                    break
                        md_table = _html_table_to_markdown(html_content)
                        if md_table:
                            page_regions.append((y_pos, md_table, "table", bbox))
                        continue

                    # 普通文字区域（text / equation / reference 等）
                    text = _extract_region_text(res)
                    if text.strip():
                        page_regions.append((y_pos, text.strip(), "paragraph", bbox))

                # 按 y 坐标排序，保证版面阅读顺序（从上到下）
                page_regions.sort(key=lambda x: x[0])

                page_text_chars = sum(len(content.strip()) for _, content, _, _ in page_regions)
                if page_text_chars < self._PURE_OCR_FALLBACK_MIN_CHARS:
                    try:
                        print(
                            f"    [OcrPipeline] 第 {page_idx + 1} 页 PP-Structure 召回不足"
                            f"（{page_text_chars} 字），启用纯 OCR 穿透兜底"
                        )
                        pure_engine = _get_pure_ocr_engine()
                        try:
                            pure_result = pure_engine.ocr(img, cls=False)
                        except TypeError:
                            pure_result = pure_engine.ocr(img)

                        pure_regions = []
                        for points, text in _flatten_pure_ocr_result(pure_result):
                            bbox = _bbox_from_points(points)
                            x1, y1, x2, y2 = bbox
                            if not text:
                                continue
                            if x2 < img_w * 0.04 or x1 > img_w * 0.96:
                                continue
                            if page_idx == 0 and y2 < img_h * 0.12:
                                continue
                            pure_regions.append((y1, text.strip(), "paragraph", bbox))

                        if pure_regions:
                            pure_regions.sort(key=lambda x: x[0])
                            page_regions = pure_regions
                            parser_type = "OCR_PURE"
                            print(f"    [OcrPipeline] 纯 OCR 兜底召回 {len(pure_regions)} 行")
                        else:
                            print(f"    [OcrPipeline] 纯 OCR 兜底仍无有效文本")
                    except Exception as pure_err:
                        print(f"    ⚠️ [OcrPipeline] 纯 OCR 兜底失败，保留 PP-Structure 结果: {pure_err}")

                page_lines = [content for _, content, _, _ in page_regions]
                page_char_count = sum(len(content.strip()) for content in page_lines)
                page_statuses.append(PageParseStatus(
                    page_idx=page_idx,
                    status="SUCCESS" if page_char_count > 0 else "EMPTY",
                    parser_type=parser_type,
                    char_count=page_char_count,
                    duration_ms=int((time.time() - page_started) * 1000),
                    error_msg="" if page_char_count > 0 else "no text extracted by OCR",
                ))

                for _, content, el_type, region_bbox in page_regions:
                    all_elements.append({
                        "text": content,
                        "type": el_type,
                        "page_idx": page_idx,
                        "bbox": [float(v) / dpi_scale for v in region_bbox] if len(region_bbox) == 4 else None,
                        "source": "ocr",
                    })

                if page_lines:
                    all_page_contents.extend(page_lines)
                    # 页间插入空行（保持页边界感知，与 PdfParser 行为一致）
                    all_page_contents.append("")

                try:
                    del structure_result
                    del img
                    del pix
                except Exception:
                    pass
                gc.collect()

                # ── 方案 B：斩断长文档扫描的 OOM 链条，强制清理显存和物理内存 ──
                if 'pix' in locals(): del pix
                if 'img' in locals(): del img
                import gc
                gc.collect()

            reported_pages = {int(item.page_idx) for item in page_statuses}
            for missing_idx in range(page_count):
                if missing_idx not in reported_pages:
                    page_statuses.append(PageParseStatus(
                        page_idx=missing_idx,
                        status="FAILED",
                        parser_type="OCR_STRUCTURE",
                        char_count=0,
                        duration_ms=0,
                        error_msg="page OCR did not produce a status",
                    ))

            report_ctx = (opts or {}).get("_report_ctx")
            if isinstance(report_ctx, dict):
                page_statuses.sort(key=lambda item: item.page_idx)
                report = ParseReport(source_name=os.path.basename(pdf_path), details=page_statuses)
                report.compute_stats()
                report_ctx["report"] = report

            doc.close()

            if not all_page_contents:
                print(f"⚠️ [OcrPipeline] OCR 结果为空，可能文档无有效文字区域: {pdf_path}")
                return None

            result_text = "\n".join(all_page_contents).strip()
            print(f"[OcrPipeline] ✅ OCR 完成，共 {len(result_text)} 字符（{page_count} 页）")

            # 写入临时 _ocr_result.txt 文件
            # suffix="_ocr_result.txt" 使 ScannedPdfParser.can_parse() 能正确路由
            with tempfile.NamedTemporaryFile(
                suffix="_ocr_result.txt",
                mode="w",
                encoding="utf-8",
                delete=False
            ) as tmp:
                tmp.write(result_text)
                out_path = tmp.name

            json_path = out_path + ".elements.json"
            try:
                with open(json_path, "w", encoding="utf-8") as jf:
                    json.dump(all_elements, jf, ensure_ascii=False)
            except Exception as json_err:
                print(f"⚠️ [OcrPipeline] OCR 元素 JSON 写入失败（不影响文本解析）: {json_err}")

            print(f"[OcrPipeline] OCR 结果写入: {out_path}")
            return out_path

        except ImportError as ie:
            print(
                f"⚠️ [OcrPipeline] PaddleOCR 未安装，无法处理扫描件。"
                f"请确认 ai-lib/paddle/paddlepaddle-*.whl（CPU版）+ paddleocr-*.whl 已包含在构建包中。"
                f"缺失包: {ie}"
            )
            return None

        except Exception as e:
            import traceback
            print(f"⚠️ [OcrPipeline] OCR 处理失败，降级至 MarkItDown: {e}")
            print(traceback.format_exc())
            return None
