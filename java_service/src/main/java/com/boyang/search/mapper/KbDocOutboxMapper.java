package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.KbDocOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_doc_outbox 表 Mapper。
 *
 * 业务功能：为 Transactional Outbox Pattern 提供数据库操作：
 *   - 按状态批量查询（供 OutboxPoller 轮询 READY 记录）
 *   - 按 taskId 精确定位（供 Python 回调时更新状态）
 *   - 原子状态推进（防止并发 Poller 实例重复处理）
 *
 * 并发安全：updateStatusByTaskId 使用 CAS 风格（WHERE status=expected）防止乱序状态回退。
 */
@Mapper
public interface KbDocOutboxMapper extends BaseMapper<KbDocOutbox> {

    /**
     * 批量查询指定状态的 Outbox 记录（按创建时间升序）。
     * 供 OutboxPoller 每次轮询时拉取待处理批次。
     *
     * @param status 目标状态（通常为 "READY"）
     * @param limit  单次拉取上限（防止单批次过大）
     * @return Outbox 记录列表
     */
    @Select("SELECT * FROM kb_doc_outbox WHERE status = #{status} " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<KbDocOutbox> findByStatusWithLimit(@Param("status") String status,
                                             @Param("limit") int limit);

    /**
     * 按 taskId 将 Outbox 记录从 fromStatus 推进到 toStatus（CAS 风格原子更新）。
     * 并发安全：WHERE status=fromStatus 保证不会对已推进的记录重复操作。
     *
     * @param taskId     任务 ID（唯一定位一条 Outbox 记录）
     * @param fromStatus 预期当前状态（CAS 前置条件）
     * @param toStatus   目标状态
     * @param errorMsg   失败原因（正常推进时传 null）
     * @return 影响行数（0 表示 CAS 失败，可能已被其他线程推进）
     */
    @Update("UPDATE kb_doc_outbox SET status = #{toStatus}, error_msg = #{errorMsg}, " +
            "updated_at = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND status = #{fromStatus}")
    int updateStatusByTaskId(@Param("taskId") String taskId,
                              @Param("fromStatus") String fromStatus,
                              @Param("toStatus") String toStatus,
                              @Param("errorMsg") String errorMsg);

    /**
     * 按 id 将 Outbox 记录推进到目标状态（供 OutboxPoller 确认激活结果时使用）。
     *
     * @param id       Outbox 主键
     * @param toStatus 目标状态（DONE 或 FAILED）
     * @param errorMsg 失败原因（成功时传 null）
     * @return 影响行数
     */
    @Update("UPDATE kb_doc_outbox SET status = #{toStatus}, error_msg = #{errorMsg}, " +
            "updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateStatusById(@Param("id") Long id,
                          @Param("toStatus") String toStatus,
                          @Param("errorMsg") String errorMsg);

    /**
     * 递增 retry_count 并可选更新失败原因（供重试逻辑使用）。
     *
     * @param id       Outbox 主键
     * @param errorMsg 本次失败原因
     * @return 影响行数
     */
    @Update("UPDATE kb_doc_outbox SET retry_count = retry_count + 1, " +
            "error_msg = #{errorMsg}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int incrementRetryCount(@Param("id") Long id, @Param("errorMsg") String errorMsg);
}
