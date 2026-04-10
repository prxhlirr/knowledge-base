package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("sys_tenant_policy")
public class SysTenantPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appCode;
    private String allowedIndices;
    private String forceFileType;
    private Integer minSecurityLevel;
    
    private Date createdAt;
    private Date updatedAt;
    
    @TableLogic
    private Integer isDeleted;

    // 适配旧版内部属性别名
    public String getIndexPattern() {
        return allowedIndices;
    }

    public String getForceSource() {
        return forceFileType;
    }

    public Integer getMinSecurity() {
        return minSecurityLevel;
    }
}
