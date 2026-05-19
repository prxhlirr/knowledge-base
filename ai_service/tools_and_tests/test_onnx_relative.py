import onnxruntime
from onnxruntime.capi.onnxruntime_inference_collection import InferenceSession
import os
import shutil

# Change working directory to the model folder
model_dir = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3"
os.chdir(model_dir)

model_file = "model.onnx"
providers = ['CPUExecutionProvider']

print(f"Testing model loading from CWD: {os.getcwd()}")
print(f"File exists: {os.path.exists(model_file)}")

try:
    print("Attempting to create InferenceSession with relative path...")
    sess = InferenceSession(model_file, providers=providers)
    print("✅ Success with relative path!")
except Exception as e:
    print(f"❌ Failed with relative path: {e}")
    import traceback
    traceback.print_exc()
