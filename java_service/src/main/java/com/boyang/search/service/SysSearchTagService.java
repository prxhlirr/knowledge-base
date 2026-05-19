package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SysSearchTag;

/**
 * 业务功能：人工打标管理与词频、向量同步服务接口
 */
public interface SysSearchTagService extends IService<SysSearchTag> {
    
    /**
     * 关键方法：同步打标的数据到 ES 和重新生成 AI 向量
     * 流程：
     * 1. 查找指定 tagId 记录。
     * 2. 从 ES 中按 doc_id 捞出所有切片 chunk 原始文档。
     * 3. 循环重生成融合了该人工 tags/keywords 文本的向量。
     * 4. Update 回 ES。
     * 5. 修改 sync_status 为 1。
     */
    void syncTagToEsAndAi(Long tagId) throws Exception;
}
