import onnxruntime
from onnxruntime.capi.onnxruntime_inference_collection import InferenceSession
import os
from pathlib import Path

model_path = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3\model.onnx"
providers = ['CPUExecutionProvider']

print(f"Testing model loading from: {model_path}")
print(f"File exists: {os.path.exists(model_path)}")
print(f"Data file exists: {os.path.exists(model_path + '.data')}")

try:
    print("Attempting to create InferenceSession...")
    sess = InferenceSession(model_path, providers=providers)
    print("✅ Success!")
except Exception as e:
    print(f"❌ Failed: {e}")
    import traceback
    traceback.print_exc()

print("\nTrying with forward slashes...")
try:
    model_path_posix = model_path.replace('\\', '/')
    sess = InferenceSession(model_path_posix, providers=providers)
    print("✅ Success with forward slashes!")
except Exception as e:
    print(f"❌ Failed with forward slashes: {e}")
