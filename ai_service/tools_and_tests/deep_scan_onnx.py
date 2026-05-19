import os
import re

model_path = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3\model.onnx"

with open(model_path, "rb") as f:
    data = f.read()
    # Search for any string that looks like a filename (contains .data, .bin, or no extension if small)
    # Most external data names follow the pattern: param_name.data or model.data
    matches = re.finditer(rb'[a-zA-Z0-9_\-\.\/\\]+\.(?:data|bin|weight)', data)
    found = False
    for match in matches:
        print(f"Found reference: {match.group().decode('utf-8', errors='ignore')}")
        found = True
    
    if not found:
        print("No external data references found in the entire model.onnx file!")
