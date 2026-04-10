import urllib.request, json

req_body = {
    "query": {
        "bool": {
            "must": [
                {
                    "multi_match": {
                        "query": "协调会议",
                        "fields": ["content^1.5", "metadata.source^1.0", "keywords^5.0"],
                        "operator": "and"
                    }
                }
            ]
        }
    },
    "size": 100,
    "_source": ["metadata.source", "content"]
}

req = urllib.request.Request(
    'http://localhost:9200/kb_document_v1/_search', 
    data=json.dumps(req_body).encode('utf-8'), 
    headers={'Content-Type': 'application/json'}
)

try:
    res = urllib.request.urlopen(req)
    data = json.loads(res.read())
    
    hits = [hit['_source']['metadata']['source'] for hit in data['hits']['hits']]
    print(f"Total hits: {len(hits)}")
    is_092_in_hits = any("关于食品药品安全监管的报告_092" in h for h in hits)
    print(f"Is _092 in hits? {is_092_in_hits}")
    
    if is_092_in_hits:
        print(f"Index of 092 in ES score order: {next(i for i, h in enumerate(hits) if '关于食品药品安全监管的报告_092' in h)}")
        
except Exception as e:
    print(f"Error: {e}")
