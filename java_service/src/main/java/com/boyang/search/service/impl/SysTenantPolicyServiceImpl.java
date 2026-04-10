package com.boyang.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.mapper.SysTenantPolicyMapper;
import com.boyang.search.service.SysTenantPolicyService;
import org.springframework.stereotype.Service;

@Service
public class SysTenantPolicyServiceImpl extends ServiceImpl<SysTenantPolicyMapper, SysTenantPolicy> implements SysTenantPolicyService {
    @Override
    public SysTenantPolicy getByAppCode(String appCode) {
        return this.getOne(new LambdaQueryWrapper<SysTenantPolicy>()
                .eq(SysTenantPolicy::getAppCode, appCode)
                .last("limit 1"));
    }
}
