package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SysDocImportTask;

public interface SysDocImportTaskService extends IService<SysDocImportTask> {
    SysDocImportTask getByTaskId(String taskId);
}
