import os
import re

model_path = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3\model.onnx"

with open(model_path, "rb") as f:
    # Read the first 1MB, should be enough to find external data paths
    data = f.read(1024 * 1024)
    # Search for anything ending in .data or .bin
    matches = re.finditer(rb'[a-zA-Z0-9_\-\.]+\.(?:data|bin)', data)
    found = False
    for match in matches:
        print(f"Found potential external data reference: {match.group().decode('utf-8', errors='ignore')}")
        found = True
    
    if not found:
        print("No .data or .bin references found in the first 1MB.")
