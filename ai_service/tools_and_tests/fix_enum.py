import json
import os

paths = [
    r'E:\project\AI\knowledge-base\models\bge-m3\tokenizer.json',
    r'E:\project\AI\knowledge-base\models\onnx_native\bge-m3\tokenizer.json'
]

for p in paths:
    if os.path.exists(p):
        with open(p, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        # 终极解决方案：完全删除引起不兼容解析异常的核心配置 node
        if 'pre_tokenizer' in data:
            del data['pre_tokenizer']
            
        with open(p, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"Aggressively patched: {p}")
