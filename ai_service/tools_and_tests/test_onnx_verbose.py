import onnxruntime
import os

model_path = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3\model.onnx"
providers = ['CPUExecutionProvider']

print("Enabling Verbose Logging...")
options = onnxruntime.SessionOptions()
options.log_severity_level = 0  # Verbose
options.log_verbosity_level = 4 # Detailed

try:
    print("Attempting to create InferenceSession with Verbose Logs...")
    sess = onnxruntime.InferenceSession(model_path, sess_options=options, providers=providers)
    print("✅ Success!")
except Exception as e:
    print(f"❌ Failed: {e}")
