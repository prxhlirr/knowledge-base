import shutil
import os
from pathlib import Path

src_dir = r"E:\project\AI\knowledge-base\models\onnx_native\bge-m3"
dst_dir = r"E:\kb_models"

if not os.path.exists(dst_dir):
    os.makedirs(dst_dir)

files = ["model.onnx", "model.onnx.data"]

for f in files:
    src = os.path.join(src_dir, f)
    dst = os.path.join(dst_dir, f)
    print(f"Copying {src} to {dst}...")
    if os.path.exists(src):
        shutil.copy2(src, dst)
        print(f"Done: {os.path.getsize(dst)} bytes")
    else:
        print(f"Source not found: {src}")

print("Copy process finished.")
