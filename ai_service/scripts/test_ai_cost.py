# -*- coding: utf-8 -*-
import requests, json

url = 'http://localhost:8001/api/ai/intent/rewrite'
payloadOuter = {'query': '成婚差不多已有两年'}
resp = requests.post(url, json=payloadOuter)
print(f'rewrite: {resp.json()}')

url2 = 'http://localhost:8001/api/ai/vector/query'
payloadOuter2 = {'text': '成婚差不多已有两年'}
resp2 = requests.post(url2, json=payloadOuter2)
costMs = resp2.json().get('data', {}).get('costMs')
print(f'vector costMs: {costMs}')
