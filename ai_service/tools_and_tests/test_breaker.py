import urllib.request, json
import time

def search(query, top_k=5):
    req_body = {
        "queryText": query,
        "pageSize": top_k
    }
    
    req = urllib.request.Request(
        'http://localhost:8080/api/v1/search', 
        data=json.dumps(req_body).encode('utf-8'), 
        headers={
            'Content-Type': 'application/json',
            'X-Search-AppCode': 'ADMIN_MASTER_KEY'
        }
    )
    
    try:
        start = time.time()
        res = urllib.request.urlopen(req)
        data = json.loads(res.read().decode('utf-8'))
        cost = int((time.time() - start) * 1000)
        records = data.get('data', {}).get('list', [])
        
        print(f"\n--- Query: '{query}' | Cost: {cost}ms ---")
        for i, r in enumerate(records):
            org = r.get('organization', 'Unknown')
            # 简单截断文本以便阅览
            snippet = r.get('chunk_text', '').replace('\n', ' ')
            if len(snippet) > 60:
                snippet = snippet[:60] + "..."
            print(f"Rank {i+1}: raw_score={r.get('raw_score')}, score={r.get('score')} | Org: {org} | Snippet: {snippet}")
            
    except Exception as e:
        print(f"Error query='{query}': {e}")

search("高质量发展", 10)
