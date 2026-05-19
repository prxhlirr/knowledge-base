import onnxruntime
from onnxruntime.capi.onnxruntime_inference_collection import InferenceSession
import os

model_path = r"E:\kb_models\model.onnx"
providers = ['CPUExecutionProvider']

print(f"Testing model loading from path without dashes: {model_path}")

try:
    sess = InferenceSession(model_path, providers=providers)
    print("✅ Success with plain path!")
except Exception as e:
    print(f"❌ Failed with plain path: {e}")
