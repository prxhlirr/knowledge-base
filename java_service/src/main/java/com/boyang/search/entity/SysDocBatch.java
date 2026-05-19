package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("sys_doc_batch")
public class SysDocBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String batchId;
    private String sourceDir;
    private String ingestMode; // UPLOAD, LOCAL_DIR, SFTP, URL
    private String sourceInfo; // 原始路径或URL备忘
    
    private Integer totalCount;
    private Integer successCount;
    private Integer errorCount;
    
    // 状态: PENDING, IMPORTING, DONE, FAILED
    private String status;
    
    private Date createdAt;
    private Date updatedAt;
    
    @TableLogic
    private Integer isDeleted;
}
