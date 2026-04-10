import onnxruntime
from onnxruntime.capi.onnxruntime_inference_collection import InferenceSession
import os

model_path = r"C:\Users\liyz\AppData\Local\Temp\onnx_test\model.onnx"
providers = ['CPUExecutionProvider']

print(f"Testing model loading from temporary C: drive path: {model_path}")
print(f"File exists: {os.path.exists(model_path)}")
print(f"Data file exists: {os.path.exists(model_path + '.data')}")

try:
    print("Attempting to create InferenceSession...")
    sess = InferenceSession(model_path, providers=providers)
    print("✅ Success on C: drive!")
except Exception as e:
    print(f"❌ Failed on C: drive: {e}")
    import traceback
    traceback.print_exc()
