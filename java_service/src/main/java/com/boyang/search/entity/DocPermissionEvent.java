package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档权限变更事件溯源实体。
 * 业务功能：记录文档从创建到归档期间所有权限变更操作的完整历史。
 * 关键字段：
 *   - docId：目标文档标识（ES _id 或 source_name）
 *   - action：变更动作（GRANT/REVOKE/HANDLER/VISIBILITY_CHANGE）
 *   - targetType：授权目标类型（USER/DEPT/GROUP）
 *   - targetValue：目标值（用户ID/部门12位编码/群组code）
 * 设计约束：本表只追加不修改，权限变化通过新增事件而非UPDATE实现。
 */
@Data
@TableName("doc_permission_events")
public class DocPermissionEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档标识（ES source_name 或 _id） */
    private String docId;

    /**
     * 变更动作：
     *   GRANT    - 授权给某用户/部门/群组
     *   REVOKE   - 撤销某用户/部门/群组的权限
     *   HANDLER  - 标记文档经手人（参与处理流程）
     *   VISIBILITY_CHANGE - 可见度级别变更（如 PRIVATE→INTERNAL）
     */
    private String action;

    /** 授权目标类型：USER / DEPT / GROUP */
    private String targetType;

    /** 目标值：用户ID / 12位部门编码 / 群组code */
    private String targetValue;

    /** 操作人ID */
    private String operatorId;

    /** 变更备注（原因、审批单号等） */
    private String remark;

    /** 事件发生时间（毫秒精度） */
    private LocalDateTime createdAt;
}
