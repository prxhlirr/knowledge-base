def build_permission_projection_from_payload(payload: dict) -> dict:
    """
    业务功能：把 Java Redis payload 中的单位权限字段转换为 RAGPipeline 识别的 snake_case 元数据。
    关键流程：优先使用 Java 预计算字段，缺失时用 deptCode 做兼容兜底，避免历史队列任务丢失单位投影。
    设计原因：组织树计算属于 Java 权限域，Python Worker 只负责透传索引字段，避免两端权限规则分叉。
    """
    payload = payload or {}
    owner_unit_code = (
        payload.get("ownerUnitCode")
        or payload.get("owner_unit_code")
        or payload.get("deptCode")
        or "global"
    )
    visible_unit_codes = (
        payload.get("visibleUnitCodes")
        or payload.get("visible_unit_codes")
        or payload.get("visible_depts")
        or []
    )
    if isinstance(visible_unit_codes, str):
        visible_unit_codes = [
            item.strip()
            for item in visible_unit_codes.replace("，", ",").split(",")
            if item.strip()
        ]
    if not visible_unit_codes:
        visible_unit_codes = [owner_unit_code]

    return {
        "owner_unit_code": owner_unit_code,
        "visible_unit_codes": visible_unit_codes,
        "permission_version": payload.get("permissionVersion") or payload.get("permission_version") or 0,
    }
