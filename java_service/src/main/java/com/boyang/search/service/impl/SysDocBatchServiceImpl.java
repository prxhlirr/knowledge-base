package com.boyang.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.mapper.SysDocBatchMapper;
import com.boyang.search.service.SysDocBatchService;
import org.springframework.stereotype.Service;

@Service
public class SysDocBatchServiceImpl extends ServiceImpl<SysDocBatchMapper, SysDocBatch> implements SysDocBatchService {
    @Override
    public SysDocBatch getByBatchId(String batchId) {
        return this.getOne(new LambdaQueryWrapper<SysDocBatch>()
                .eq(SysDocBatch::getBatchId, batchId)
                .last("limit 1"));
    }
}
