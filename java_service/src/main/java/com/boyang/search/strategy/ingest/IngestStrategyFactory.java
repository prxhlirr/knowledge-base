package com.boyang.search.strategy.ingest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略工厂，负责在系统启动时收集所有 IngestStrategy 的实现
 * 提供根据 ingestType 获取对应实现服务的能力，从根本上消灭 switch-case。
 */
@Component
public class IngestStrategyFactory {

    private final Map<String, IngestStrategy> strategyMap = new HashMap<>();

    @Autowired
    public IngestStrategyFactory(List<IngestStrategy> strategies) {
        for (IngestStrategy strategy : strategies) {
            strategyMap.put(strategy.getStrategyType().toUpperCase(), strategy);
        }
    }

    public IngestStrategy getStrategy(String ingestType) {
        if (ingestType == null || ingestType.trim().isEmpty()) {
            throw new IllegalArgumentException("Ingest type could not be empty.");
        }
        IngestStrategy strategy = strategyMap.get(ingestType.toUpperCase());
        if (strategy == null) {
            throw new IllegalArgumentException("不受支持的 ingestType: " + ingestType + "，当前支持的类型有: " + strategyMap.keySet());
        }
        return strategy;
    }
}
