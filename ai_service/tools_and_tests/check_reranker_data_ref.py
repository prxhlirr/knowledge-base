import os
import re

model_path = r"E:\project\AI\knowledge-base\models\onnx_native\reranker\model.onnx"

with open(model_path, "rb") as f:
    content = f.read()
    # Find any .data references
    matches = re.findall(rb'[a-zA-Z0-9_\-\.]+\.data', content)
    print(f"Reranker .data references: {matches}")
