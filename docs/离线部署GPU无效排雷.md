# AI 服务 GPU 容器化部署——完整排雷手册

> 本文档记录 `knowledge-base-ai` 服务从 CPU 推理迁移至 GPU 加速的完整排雷过程。环境：Python 3.10 / ONNX Runtime GPU / CUDA 12.x / Docker + nvidia-container-toolkit。

---

## 一、 本次排雷全景图

```
问题链：
  镜像构建(Torch/cuDNN) → 容器启动(torch import) → GPU 未激活(CUDA Provider 回退)
       ↓                         ↓                          ↓
  [地雷1] cuDNN 路径错误    [地雷2] 僵尸 import      [地雷3] docker runtime 未生效
```

---

## 二、 地雷逐一拆解

### 🚩 地雷 1：CUDA/cuDNN 库 bind mount 路径拼写错误
**现象**：容器能启动，但 `CUDAExecutionProvider` 加载时报 `libcudnn not found`，即使宿主机有 CUDA。

**根因**：[docker-compose.yml](file:///e:/project/AI/knowledge-base/ai_service/docker-compose.yml) 的 `volumes.source` 路径中把 `targets` 写成了 `target`（缺少一个 `s`）：
```diff
- source: /usr/local/cuda-12.4/target/x86_64-linux/lib   # ❌ 拼写错误
+ source: /usr/local/cuda-12.4/targets/x86_64-linux/lib  # ✅ 正确
```

**教训**：bind mount 路径完全静默失败，不会报错，只会挂一个空目录进去。务必在部署后执行 `ls /usr/local/cuda-12.4/targets/x86_64-linux/lib` 验证挂载内容非空。

---

### 🚩 地雷 2：[rag_pipeline.py](file:///e:/project/AI/knowledge-base/ai_service/scripts/rag_pipeline.py) 顶部遗留僵尸导入
**现象**：移除 `torch` 依赖后容器无限重启，日志报 `ModuleNotFoundError: No module named 'torch'`。

**根因**：在老版本代码中，推理是通过 `SentenceTransformer`（依赖 `torch`) 完成的。后来已重构为 `onnxruntime` + 纯 `numpy`，但 [rag_pipeline.py](file:///e:/project/AI/knowledge-base/ai_service/scripts/rag_pipeline.py) 顶部的 `import torch` 和 `from sentence_transformers import SentenceTransformer` 没有一并删除，成了悬空的僵尸导入。

```diff
# scripts/rag_pipeline.py
  import os
- import torch
  import time
- from sentence_transformers import SentenceTransformer
  ...
- DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
```

**教训**：重构业务逻辑时，必须同步使用 `grep -r "torch"` 或 IDE 的"查找所有引用"扫描并删除孤立依赖。

---

### 🚩 地雷 3：Docker `runtime: nvidia` 在 Compose V2 中被覆盖
**现象**：[docker-compose.yml](file:///e:/project/AI/knowledge-base/ai_service/docker-compose.yml) 已经写了 `runtime: nvidia`，但 Linux 服务器上容器仍以 `runc` 启动，`/usr/local/nvidia/lib64` 不存在。

**根因**：
1. `docker info` 显示 `default runtime: runc`。
2. 当同时声明了 `runtime: nvidia` 和 `deploy.resources.reservations.devices` 时，不同版本 Docker Compose 的行为不一致，在某些版本中 `runtime` 字段会被静默忽略。

**根治方案**：在 Linux 宿主机 `/etc/docker/daemon.json` 中将 nvidia 设为全局默认 runtime，彻底绕开 Compose 字段解析的不一致问题：

```json
{
  "default-runtime": "nvidia",
  "runtimes": {
    "nvidia": {
      "path": "nvidia-container-runtime",
      "runtimeArgs": []
    }
  }
}
```

```bash
sudo systemctl restart docker
docker compose up -d --force-recreate
```

**验证**：
```bash
docker inspect ai-service | grep -i '"Runtime"'
# 期望输出："Runtime": "nvidia"
```

---

## 三、 镜像体积优化（额外战果）

| 操作 | 节省体积 |
|---|---|
| 删除 `torch` (~800MB) | 减少约 800 MB |
| 删除 `Sentence-Transformers` | 减少约 30 MB |
| 删除 `langchain-text-splitters`、`pypdf`、`python-docx` | 减少约 80 MB |
| 引入 `nvidia-cudnn-cu12` wheel 自包含 cuDNN | +571 MB（换取无需 bind mount） |

最终镜像从 **约 6.7 GB** 缩减至 **约 5.9 GB**，且 cuDNN 已自包含。

---

## 四、 一次性 GPU 诊断命令（下次排查直接用）

```bash
docker exec ai-service python -c "
import os,ctypes,subprocess
print('[1] LD_LIBRARY_PATH:',os.environ.get('LD_LIBRARY_PATH','<未设置>'))
for lib in ['libcuda.so.1','libcudart.so.12','libcublasLt.so.12','libcudnn.so.9']:
    try: ctypes.CDLL(lib); print(f'  ✅ {lib}')
    except Exception as e: print(f'  ❌ {lib}: {e}')
r=subprocess.run(['nvidia-smi','--query-gpu=name,memory.free','--format=csv,noheader'],capture_output=True,text=True,timeout=3)
print('[2] GPU:',r.stdout.strip() or r.stderr.strip())
import onnxruntime as ort
print('[3] ORT Providers:',ort.get_available_providers())
"
```

---

## 五、 正确的 GPU 服务部署清单

在 Linux 离线服务器执行以下步骤，确保 GPU 加速服务 100% 激活：

- [ ] **1. 宿主机**：检查 `nvidia-container-toolkit` 已安装 (`docker info | grep nvidia`)
- [ ] **2. 宿主机**：将 nvidia 设为默认 runtime（修改 `/etc/docker/daemon.json`，重启 docker）
- [ ] **3. 导入镜像**：`docker load -i knowledge-base-ai-1.0.0.tar`
- [ ] **4. 验证镜像挂载**：容器启动后执行诊断命令，确认 `libcudnn.so.9` ✅
- [ ] **5. 验证 runtime**：`docker inspect ai-service | grep -i Runtime` 确认为 `nvidia`
- [ ] **6. 验证 ORT 设备**：`docker logs ai-service 2>&1 | grep "实际运行设备"` 确认 `CUDA`
