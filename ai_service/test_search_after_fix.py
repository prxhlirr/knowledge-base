import urllib.request, json
url = 'http://192.168.74.1:8080/api/v1/search'
data = {'queryText': '月华', 'pageSize': 5}
req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers={'Content-type': 'application/json', 'X-Search-AppCode':'ADMIN_MASTER_KEY', 'X-User-Id':'system'})
resp = urllib.request.urlopen(req)
res = json.loads(resp.read())
print("Top hits:")
for i, d in enumerate(res['data']['list']):
  print(f"[{i+1}] Score: {d.get('score')} (Raw: {d.get('raw_score')}) | Source: {d.get('_source', {}).get('metadata', {}).get('source')}")
