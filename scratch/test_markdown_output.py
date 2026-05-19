import sys
import os
import json

# 注入 AI Service 的路径，以便在容器内导入 core
sys.path.append("/app")

# 拦截全局 np.sctypes 缺失漏洞，保障 imgaug 等库的兼容性
import numpy as np
if not hasattr(np, "sctypes"):
    np.sctypes = {
        'int': [np.int8, np.int16, np.int32, np.int64],
        'uint': [np.uint8, np.uint16, np.uint32, np.uint64],
        'float': [np.float16, np.float32, np.float64],
        'others': [np.bool_, np.complex64, np.complex128]
    }

from core.parsing.pdf_parser import PdfParser

def test_parse():
    pdf_path = "/workspace/stress_test_6pages_scanned.pdf"
    # 如果容器挂载到了根目录，物理路径可能是 e:\project\AI\knowledge-base。
    # 我们在 docker exec 时映射该路径，最快的办法是直接在容器内跑，并指向我们刚才 docker cp 进容器或者本地挂载的 stress_test 物理文件路径。
    # 检查容器中是否存在该文件。
    
    print(f"Checking if PDF exists at {pdf_path}...")
    # 我们可以动态寻找
    possible_paths = [
        "/app/stress_test_6pages_scanned.pdf",
        "/app/core/stress_test_6pages_scanned.pdf",
        "/app/data/stress_test_6pages_scanned.pdf"
    ]
    
    target_path = None
    for p in possible_paths:
        if os.path.exists(p):
            target_path = p
            break
            
    if not target_path:
        # 兜底：直接从挂载的 /workspace 或者由我们 docker cp 塞进容器的临时路径寻找
        target_path = "/tmp/stress_test_6pages_scanned.pdf"
        if not os.path.exists(target_path):
            print(f"CRITICAL: Cannot find PDF at current accessible paths.")
            return
            
    print(f"Parsing file: {target_path}")
    parser = PdfParser()
    # parser.parse() 返回的数据结构包含了 markdown 内容
    result = parser.parse(target_path)
    
    # 我们把 markdown 存下来
    md_content = result.get("markdown", "EMPTY")
    out_md = "/tmp/debug_output.md"
    with open(out_md, "w", encoding="utf-8") as f:
                f.write(md_content)
                
    print(f"Successfully parsed Markdown! Output written to: {out_md}")
    print("Markdown Preview (First 500 chars):")
    print(md_content[:500])
    
    # 顺便把 layout regions 打印一下看看
    pages = result.get("pages", [])
    print(f"\nTotal pages parsed: {len(pages)}")

if __name__ == "__main__":
    test_parse()
