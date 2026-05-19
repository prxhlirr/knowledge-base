package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SysAiTuningConfig;

public interface SysAiTuningConfigService extends IService<SysAiTuningConfig> {

    /**
     * 获取全局唯一的超参动态调配项
     * 如果表为空则初始化一条预设记录
     */
    SysAiTuningConfig getGlobalConfig();

    /**
     * 更新全局配置并立即同步至 AI 模型服务
     */
    boolean updateConfigAndNotifyAi(SysAiTuningConfig config);
}
