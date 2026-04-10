# -*- coding: utf-8 -*-
import requests, time, json

url = 'http://localhost:8080/api/v1/search'
payload = {'queryText': '成婚差不多已有两年', 'pageSize': 10}
headers = {'Content-Type': 'application/json', 'X-Search-AppCode': 'ADMIN_MASTER_KEY'}

print(f"Testing search for: {payload['queryText']}")
start = time.time()
try:
    resp = requests.post(url, json=payload, headers=headers)
    cost = time.time() - start
    print(f'请求耗时: {cost:.2f}s')
    if resp.status_code == 200:
        data = resp.json().get('data', {})
        results = data.get('list', [])
        print(f'Java reported took_ms: {data.get("took_ms")} ms')
        print(f'结果数量: {len(results)}')
        for item in results[:3]:
            org = item.get('organization', '')
            score = item.get('score', 0.0)
            id_ = item.get('_id', '')
            print(f'- [{id_}] {org} | 分数: {score}')
    else:
        print(f"Error {resp.status_code}: {resp.text}")
except Exception as e:
    print(e)
