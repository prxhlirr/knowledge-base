package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("sys_doc_import_task")
public class SysDocImportTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String taskId;
    private String batchId;
    private String filePath;
    private String originalName;
    
    // 状态机记录: IDLE, PENDING, PARSING, INDEXED, ERROR
    private String status;
    private String errorMsg;

    /**
     * [B-3 修复] 文档可见度（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT），入库时记录原始值。
     * RecoveryJob 重推时从此字段读取，防止恢复时权限静默降级为 INTERNAL。
     */
    private String visibility;

    /**
     * [B-3 修复] 文档部门编码（visibility=DEPT 时非空），入库时记录原始值。
     * RecoveryJob 重推时从此字段读取，保证恢复后的文档归属正确部门。
     */
    private String deptCode;

    private Date createdAt;
    private Date updatedAt;

    @TableLogic
    private Integer isDeleted;
}
