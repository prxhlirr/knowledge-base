package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SysDocBatch;

public interface SysDocBatchService extends IService<SysDocBatch> {
    SysDocBatch getByBatchId(String batchId);
}
