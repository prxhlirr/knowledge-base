import requests
import json
from requests.auth import HTTPBasicAuth

es_url = "http://192.168.74.1:9200"
auth = HTTPBasicAuth("admin", "8732391")

def query_es():
    print("Searching for indices...")
    try:
        search_payload = {
            "query": {
                "query_string": {
                    "query": "*stress_test_6pages*"
                }
            },
            "size": 100
        }
        
        print("\nQuerying document chunks...")
        search_url = f"{es_url}/kb_document_*/_search"
        res = requests.post(search_url, json=search_payload, auth=auth)
        
        result_json = res.json()
        hits = result_json.get("hits", {}).get("hits", [])
        
        print(f"\nFound {len(hits)} document chunks from Elasticsearch.")
        
        # 收集所有分片用于保存
        output_data = []
        for i, hit in enumerate(hits):
            source = hit.get("_source", {})
            doc_id = hit.get("_id")
            
            content = source.get("content", "EMPTY")
            metadata = source.get("metadata", {})
            keywords = source.get("keywords", [])
            
            output_data.append({
                "index": i + 1,
                "doc_id": doc_id,
                "content": content,
                "metadata": metadata,
                "keywords": keywords
            })
            
        # 写入到一个格式化的 JSON 文件中，强制 utf-8 存储
        out_path = r"e:\project\AI\knowledge-base\scratch\stress_test_chunks.json"
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(output_data, f, ensure_ascii=False, indent=2)
            
        print(f"Successfully written result to: {out_path}")
            
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    query_es()
