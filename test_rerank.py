import requests

url = "http://localhost:8000/api/ai/rerank"
payload = {
    "query": "数字政府",
    "documents": [
        "市政府办公厅文件 政务（2024）008号 关于加强数字政府建设的实施方案各有关单位：为了深入贯彻落实国家关于加强数字政府建设的实施方案的相关精神... 市政府办公厅 2024年3月10日",
        "一些不相关的内容",
    ]
}

response = requests.post(url, json=payload)
print(response.json())
