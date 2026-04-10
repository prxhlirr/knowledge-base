package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SearchAuditLog;

/**
 * 业务功能：搜索审计日志服务接口
 */
public interface SearchAuditLogService extends IService<SearchAuditLog> {
    
    /**
     * 异步保存搜索日志
     * @param log 审计日志对象
     */
    void saveAsync(SearchAuditLog log);
}
