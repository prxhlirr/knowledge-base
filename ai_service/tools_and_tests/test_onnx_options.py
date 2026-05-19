import onnxruntime
from onnxruntime.capi.onnxruntime_inference_collection import InferenceSession
import os

model_path = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3\model.onnx"
providers = ['CPUExecutionProvider']

print(f"Testing model loading with disabled mmap...")
options = onnxruntime.SessionOptions()
# 0 = Default, 1 = Sequential, 2 = Random
options.execution_mode = onnxruntime.ExecutionMode.ORT_SEQUENTIAL
# 尝试禁用某些内存优化，看是否能避开 file_size 问题
options.add_session_config_entry("session.use_mmap", "0")

try:
    print("Attempting to create InferenceSession with custom options...")
    sess = InferenceSession(model_path, sess_options=options, providers=providers)
    print("✅ Success with mmap disabled!")
except Exception as e:
    print(f"❌ Failed with custom options: {e}")
    import traceback
    traceback.print_exc()
