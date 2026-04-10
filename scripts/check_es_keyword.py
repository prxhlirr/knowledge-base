import urllib.request, json
import sys

req = urllib.request.Request(
    'http://localhost:9200/kb_document_v1/_search', 
    data=json.dumps({
        'query': {
            'match': {'metadata.source': '关于食品药品安全监管的报告_092.docx'}
        },
        'size': 100
    }).encode('utf-8'), 
    headers={'Content-Type': 'application/json'}
)

try:
    res = urllib.request.urlopen(req)
    data = json.loads(res.read())
    
    found_keyword = False
    for hit in data['hits']['hits']:
        content = hit['_source']['content']
        source = hit['_source']['metadata']['source']
        if "协调会议" in content:
            found_keyword = True
            print(f"✅ Found in chunk: {hit['_source']['metadata']['chunk_id']}")
            print(f"Content snippet: {content[:200]}...")
            
    if not found_keyword:
        print(f"❌ Keyword '协调会议' NOT FOUND in any chunk of {source}. Total chunks: {len(data['hits']['hits'])}")
        # print first chunk to see what's in it
        if data['hits']['hits']:
            print(f"Sample content: {data['hits']['hits'][0]['_source']['content'][:300]}")
            
except Exception as e:
    print(f"Error: {e}")
