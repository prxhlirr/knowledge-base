import urllib.request, json
try:
    req = urllib.request.Request('http://localhost:8080/api/v1/admin/tuning/config')
    res = urllib.request.urlopen(req)
    data = json.loads(res.read().decode('utf-8'))
    print("Tuning Config:", json.dumps(data, indent=2, ensure_ascii=False))
except Exception as e:
    print("Error:", e)
