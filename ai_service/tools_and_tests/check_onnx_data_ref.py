import os

model_path = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3\model.onnx"
data_filename = "model.onnx.data"

with open(model_path, "rb") as f:
    content = f.read()
    if data_filename.encode() in content:
        print(f"✅ Found collective data reference '{data_filename}' in model.onnx")
    else:
        print(f"❌ Could NOT find collective data reference '{data_filename}' in model.onnx")
        # Try finding ANY string ending in .data
        import re
        matches = re.findall(rb'[a-zA-Z0-9_\-\.]+\.data', content)
        if matches:
            print(f"Found other .data references: {matches}")
        else:
            print("No .data references found in the entire file.")
