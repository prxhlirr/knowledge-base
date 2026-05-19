import urllib.request, json

req_body = {
    "queryText": "高质量发展",
    "pageSize": 5
}
req = urllib.request.Request(
    'http://localhost:8080/api/v1/search', 
    data=json.dumps(req_body).encode('utf-8'), 
    headers={'Content-Type': 'application/json', 'X-Search-AppCode': 'ADMIN_MASTER_KEY'}
)
try:
    res = urllib.request.urlopen(req)
    data = json.loads(res.read().decode('utf-8'))
    for i, r in enumerate(data.get('data', {}).get('list', [])):
        print(f"Rank {i+1}: raw_score={r.get('raw_score')}, score={r.get('score')}, org={r.get('organization')}")
except Exception as e:
    print(e)
