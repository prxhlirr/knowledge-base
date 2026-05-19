import sys
import os
import numpy as np

# 将项目根目录添加到路径
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from core.model_manager import model_manager

def debug_output():
    model_manager.load_model()
    if model_manager.model is None:
        print("Model not loaded")
        return

    print("\n=== Model Input Nodes ===")
    for i in model_manager.model.get_inputs():
        print(f"Name: {i.name}, Shape: {i.shape}, Type: {i.type}")

    print("\n=== Model Output Nodes ===")
    for o in model_manager.model.get_outputs():
        print(f"Name: {o.name}, Shape: {o.shape}, Type: {o.type}")

    text = "测试文本"
    inputs = model_manager.tokenizer([text], padding=True, truncation=True, max_length=512, return_tensors="np")
    model_input_names = [i.name for i in model_manager.model.get_inputs()]
    input_feed = {k: inputs[k].astype(np.int64) for k in model_input_names if k in inputs}
    
    outputs = model_manager.model.run(None, input_feed)
    print("\n=== Actual Output Shapes ===")
    for i, out in enumerate(outputs):
        print(f"Index {i}: Shape {out.shape}")

if __name__ == "__main__":
    debug_output()
