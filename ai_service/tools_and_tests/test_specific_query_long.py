# -*- coding: utf-8 -*-
import requests, json, time

url = 'http://localhost:8080/api/v1/search'
payloadOuter = {'queryText': '成婚差不多已有两年', 'pageSize': 10}
headers = {'Content-Type': 'application/json', 'X-Search-AppCode': 'ADMIN_MASTER_KEY'}

start = time.time()
resp = requests.post(url, json=payloadOuter, headers=headers)
print(f"Time: {time.time() - start:.2f}s")

data = resp.json()
list = data.get('data', {}).get('list', [])

print(f"API 返回数据量: {len(list)}")
for item in list:
    print(item.get('chunk_text', '')[:100].replace('\n', ' '))
