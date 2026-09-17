import json
import requests

def main():
    url = "http://localhost:8080/api/v1/search"
    payload = {
        "queryText": "静宁县",
        "pageSize": 10,
        "pageNum": 1,
        "searchMode": "keyword",
        "filters": {}
    }
    headers = {
        "Content-Type": "application/json",
        "X-Search-AppCode": "ADMIN_MASTER_KEY"
    }
    
    try:
        resp = requests.post(url, json=payload, headers=headers)
        print("Status Code:", resp.status_code)
        data = resp.json()
        print("Response Code:", data.get("code"))
        print("Response Msg:", data.get("msg"))
        
        results = data.get("data", {}).get("list", [])
        print(f"Total results: {len(results)}")
        
        for i, doc in enumerate(results):
            print(f"\n[结果 {i+1}] file_name: {doc.get('file_name')} | doc_id: {doc.get('doc_id')}")
            print(f"  - chunk_text (长度: {len(doc.get('chunk_text', ''))}): {repr(doc.get('chunk_text'))}")
            chunks = doc.get("chunks", [])
            print(f"  - chunks 数量: {len(chunks)}")
            for j, chunk in enumerate(chunks):
                print(f"    * chunk {j+1} | Index: {chunk.get('chunk_index')} | ID: {chunk.get('chunk_id')}")
                print(f"      matched_terms: {chunk.get('matched_terms')}")
                print(f"      chunk_text: {repr(chunk.get('chunk_text'))}")
                
    except Exception as e:
        print("请求发生异常:", e)

if __name__ == "__main__":
    main()
