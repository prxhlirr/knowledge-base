package com.boyang.search.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SearchAuditLog;
import com.boyang.search.mapper.SearchAuditLogMapper;
import com.boyang.search.service.SearchAuditLogService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 业务功能：搜索审计日志服务实现类
 */
@Service
public class SearchAuditLogServiceImpl extends ServiceImpl<SearchAuditLogMapper, SearchAuditLog> implements SearchAuditLogService {

    @Async
    @Override
    public void saveAsync(SearchAuditLog log) {
        // 执行日志保存逻辑
        this.save(log);
    }
}
