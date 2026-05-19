package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SysTenantPolicy;

public interface SysTenantPolicyService extends IService<SysTenantPolicy> {
    SysTenantPolicy getByAppCode(String appCode);
}
