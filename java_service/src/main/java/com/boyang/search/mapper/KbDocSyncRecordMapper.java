package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.KbDocSyncRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 第三方文档增量同步映射表 Mapper。
 * <p>
 * 关键自定义方法：
 *   - {@link #claimPending}：PostgreSQL 原子抢占（UPDATE...RETURNING），多节点并发安全，
 *     抢到的记录即时变 DISPATCHED，其他节点看不到，杜绝重复处理。
 *   - {@link #maxSourceUpdateTime}：时间戳游标水位线（增量发现用）。
 * 其余 CRUD 复用 MyBatis-Plus {@link BaseMapper}。
 */
@Mapper
public interface KbDocSyncRecordMapper extends BaseMapper<KbDocSyncRecord> {

    /**
     * 原子抢占一批 PENDING 记录为 DISPATCHED（PostgreSQL UPDATE...RETURNING）。
     * 抢占式拉取保证多节点/重叠调度下同一记录只被一个执行体处理。
     */
    @Select("UPDATE kb_doc_sync_record " +
            "SET sync_status='DISPATCHED', dispatched_at=NOW(), updated_at=NOW() " +
            "WHERE id IN (SELECT id FROM kb_doc_sync_record " +
            "             WHERE sync_status='PENDING' AND source_system=#{sourceSystem} " +
            "             ORDER BY id LIMIT #{limit}) " +
            "RETURNING *")
    List<KbDocSyncRecord> claimPending(@Param("sourceSystem") String sourceSystem, @Param("limit") int limit);

    /**
     * 获取指定来源系统的最大 source_update_time（增量游标水位线）。
     * 无记录时返回 null（调用方按从源头拉取处理）。
     */
    @Select("SELECT MAX(source_update_time) FROM kb_doc_sync_record WHERE source_system=#{sourceSystem}")
    OffsetDateTime maxSourceUpdateTime(@Param("sourceSystem") String sourceSystem);
}
