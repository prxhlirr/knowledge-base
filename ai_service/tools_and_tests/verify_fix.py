import onnxruntime
import os

embedding_path = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3\model.onnx"
reranker_path = r"E:\project\AI\knowledge-base\models\onnx_native\reranker\model.onnx"
providers = ['CPUExecutionProvider']

try:
    print("Testing Embedding...")
    sess1 = onnxruntime.InferenceSession(embedding_path, providers=providers)
    print("✅ Embedding success!")
    
    print("Testing Reranker...")
    sess2 = onnxruntime.InferenceSession(reranker_path, providers=providers)
    print("✅ Reranker success!")
except Exception as e:
    print(f"❌ Failed: {e}")
