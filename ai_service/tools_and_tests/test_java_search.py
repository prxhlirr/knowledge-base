import urllib.request, json
import sys

def search(top_k):
    req_body = {
        "queryText": "协调会议",
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
        res = urllib.request.urlopen(req)
        data = json.loads(res.read().decode('utf-8'))
        records = data.get('data', {}).get('list', [])
        
        found = False
        for i, r in enumerate(records):
            org = r.get('organization', 'Unknown')
            if "092" in org:
                print(f"[TopK={top_k}] ✅ Found 092 at Rank {i+1} | Score: {r.get('score')} | Org: {org}")
                found = True
        if not found:
             print(f"[TopK={top_k}] ❌ File 092 not found in top {len(records)} results")
            
    except Exception as e:
        print(f"Error topK={top_k}: {e}")

search(5)
search(10)
search(20)
search(50)
search(100)
