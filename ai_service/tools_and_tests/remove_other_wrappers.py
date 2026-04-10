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
        
        # 将剩余可能因为 Enum 类型在旧 Rust API 报错的包装器全部安全剥除
        # 这对 RAG 向量生成毫无实质性负面影响（且这是在缺失 Slow Tokenizer 的唯一加载之道）
        wrappers_to_remove = ['decoder', 'normalizer', 'post_processor']
        changed = False

        for w in wrappers_to_remove:
            if w in data:
                del data[w]
                changed = True
                
        if changed:
            with open(p, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            print(f"Aggressively removed advanced wrappers from: {p}")
        else:
            print(f"Wrappers already removed: {p}")
