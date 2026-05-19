package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SysFileParseLog;
import java.util.Map;

public interface ISysFileParseLogService extends IService<SysFileParseLog> {
    void handlePythonCallback(Map<String, Object> logData);
    void softRetryTask(String fileCode);
}
