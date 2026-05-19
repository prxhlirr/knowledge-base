import json, os

paths = [
    r'E:\project\AI\knowledge-base\models\bge-m3\tokenizer.json',
    r'E:\project\AI\knowledge-base\models\onnx_native\bge-m3\tokenizer.json',
    r'E:\project\AI\knowledge-base\models\onnx_native\reranker\tokenizer.json'
]

for p in paths:
    if os.path.exists(p):
        with open(p, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        # 移除高版本 tokenizers 产生的无法识别的属性
        changed = False
        pt = data.get('pre_tokenizer')
        if pt and 'pretokenizers' in pt:
            for item in pt['pretokenizers']:
                if item.get('type') == 'Metaspace':
                    if 'prepend_scheme' in item:
                        del item['prepend_scheme']
                        changed = True
                    if 'split' in item:
                        del item['split']
                        changed = True
        
        if changed:
            with open(p, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            print(f"Patched: {p}")
        else:
            print(f"No patch needed: {p}")
