package com.boyang.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysDocImportTask;
import com.boyang.search.mapper.SysDocImportTaskMapper;
import com.boyang.search.service.SysDocImportTaskService;
import org.springframework.stereotype.Service;

@Service
public class SysDocImportTaskServiceImpl extends ServiceImpl<SysDocImportTaskMapper, SysDocImportTask> implements SysDocImportTaskService {
    @Override
    public SysDocImportTask getByTaskId(String taskId) {
        return this.getOne(new LambdaQueryWrapper<SysDocImportTask>()
                .eq(SysDocImportTask::getTaskId, taskId)
                .last("limit 1"));
    }
}
