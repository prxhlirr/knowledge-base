import base64
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request


BASE_URL = "http://127.0.0.1:18080"
SECRET = "default-dev-secret-must-change-in-prod-32chars"
NONCE = str(int(time.time() * 1000))


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def make_jwt(user_id: str, dept_code: str, app_code: str, roles):
    """为真实 HTTP 回归生成 HS256 JWT；这里复用本地开发密钥，只用于测试链路。"""
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "userId": user_id,
        "deptCode": dept_code,
        "appCode": app_code,
        "roles": roles,
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
        + "."
        + b64url(json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    )
    signature = hmac.new(SECRET.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return signing_input + "." + b64url(signature)


def wait_until_ready(timeout_seconds=90):
    """等待 Java HTTP 端口可用；避免 Spring Boot 仍在初始化时误判接口失败。"""
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            req = urllib.request.Request(BASE_URL + "/api/v1/search", method="OPTIONS")
            urllib.request.urlopen(req, timeout=3).read()
            return
        except Exception as exc:
            last_error = exc
            time.sleep(2)
    raise RuntimeError(f"Java service not ready: {last_error}")


def search(name: str, query: str, roles, user_id: str = "u-http", dept_code: str = "620102000000"):
    """调用真实 /api/v1/search，并返回可审计的结果摘要。"""
    token = make_jwt(user_id, dept_code, "VEND_A_7788", roles)
    body = {
        "queryText": query,
        "pageSize": 10,
        "pageNum": 1,
        "searchMode": "keyword",
        "filters": {"strict_probe_nonce": NONCE + "-" + name},
    }
    req = urllib.request.Request(
        BASE_URL + "/api/v1/search",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        payload = json.loads(exc.read().decode("utf-8", errors="replace") or "{}")
        return {
            "name": name,
            "http_status": exc.code,
            "error": payload,
        }

    data = payload.get("data") or {}
    items = data.get("list") or []
    source_indexes = []
    missing_projection = 0
    for item in items:
        source_index = item.get("source_index")
        if source_index and source_index not in source_indexes:
            source_indexes.append(source_index)
        if not item.get("source_index") or not item.get("visible_unit_codes"):
            missing_projection += 1
    return {
        "name": name,
        "http_status": 200,
        "code": payload.get("code"),
        "total": data.get("total"),
        "returned": len(items),
        "source_indexes": source_indexes,
        "missing_projection": missing_projection,
        "first_file": items[0].get("file_name") if items else None,
    }


def main():
    wait_until_ready()
    cases = [
        ("official_reader_official_query", "重点民生实事工程", ["official_reader"], "u-official"),
        ("public_reader_public_query", "乡镇领导班子候选人", ["public_reader"], "u-public"),
        ("no_reader_official_query", "重点民生实事工程", [], "u-none"),
        ("admin_official_query", "重点民生实事工程", ["SYS_ADMIN"], "u-admin"),
    ]
    results = [search(name, query, roles, user_id) for name, query, roles, user_id in cases]
    print(json.dumps(results, ensure_ascii=False, indent=2))
    failures = []
    by_name = {item["name"]: item for item in results}
    if "kb_document_public" in by_name["official_reader_official_query"].get("source_indexes", []):
        failures.append("official_reader leaked public index")
    if "kb_document_official" in by_name["public_reader_public_query"].get("source_indexes", []):
        failures.append("public_reader leaked official index")
    if by_name["no_reader_official_query"].get("returned", 0) > 0:
        failures.append("no_reader returned protected documents")
    if by_name["admin_official_query"].get("returned", 0) <= 0:
        failures.append("admin returned no documents")
    for item in results:
        if item.get("missing_projection", 0) > 0:
            failures.append(f"{item['name']} has results missing permission projection")
    if failures:
        print(json.dumps({"failures": failures}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
