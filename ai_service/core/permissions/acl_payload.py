import json


def resolve_acl_tokens_from_metadata(ext_metadata: dict, missing_policy: str = "no_access") -> list:
    """
    业务功能：从 Java/Python 入库元数据中解析 ES 权限 token。
    关键流程：优先解析 acl_tokens_json；兼容旧 acl_tokens 逗号字符串；缺失或解析失败时按策略处理。
    设计原因：acl_tokens 是 ES 前置权限过滤的核心字段，缺失时不能固定降级为内部可见，必须支持严格拒绝。

    :param ext_metadata: 入库扩展元数据
    :param missing_policy: internal=兼容旧行为；no_access=写入不可访问 token；error=直接失败
    :return: ACL token 列表
    """
    ext_metadata = ext_metadata or {}
    policy = (missing_policy or "no_access").strip().lower()
    acl_tokens_json = ext_metadata.get("acl_tokens_json", "")

    if acl_tokens_json:
        try:
            tokens = json.loads(acl_tokens_json)
            return _normalize_tokens(tokens)
        except Exception as exc:
            return _handle_missing_or_invalid(policy, f"acl_tokens_json 解析失败: {exc}")

    legacy_tokens = ext_metadata.get("acl_tokens", "")
    if legacy_tokens:
        if isinstance(legacy_tokens, list):
            return _normalize_tokens(legacy_tokens)
        return _normalize_tokens([item.strip() for item in str(legacy_tokens).split(",")])

    return _handle_missing_or_invalid(policy, "acl_tokens_json 为空且无遗留 acl_tokens 字段")


def _normalize_tokens(tokens) -> list:
    if not isinstance(tokens, list):
        raise ValueError("ACL token 必须是数组")
    normalized = []
    for token in tokens:
        text = str(token).strip() if token is not None else ""
        if text and text not in normalized:
            normalized.append(text)
    if not normalized:
        raise ValueError("ACL token 数组不能为空")
    return normalized


def _handle_missing_or_invalid(policy: str, reason: str) -> list:
    if policy == "error":
        raise ValueError(reason)
    if policy == "no_access":
        return ["_NO_ACCESS"]
    return ["_INTERNAL"]
