package com.yuzhuang.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuang.outbox.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Outbox 发件箱 Mapper。
 *
 * <p>除 MyBatis-Plus BaseMapper CRUD 外，提供 Outbox → Redis Streams 可靠投递
 * 所需的原子状态流转语句（PENDING → PUBLISHED / FAILED → PROCESSED）。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 拉取一批待投递事件：status=PENDING 且 retry_count < maxRetry，按 id 升序取 limit 条。
     * 保证同一轮扫描顺序稳定、可续跑，避免无限重试拖垮下游。
     */
    @Select("SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at "
            + "FROM t_outbox_event "
            + "WHERE status = 'PENDING' AND retry_count < #{maxRetry} "
            + "ORDER BY id ASC "
            + "LIMIT #{limit}")
    List<OutboxEvent> selectPendingBatch(@Param("limit") int limit, @Param("maxRetry") int maxRetry);

    /**
     * 发布成功确认：仅当仍为 PENDING 时置为 PUBLISHED（CAS 防止并发扫描重复覆盖状态）。
     */
    @Update("UPDATE t_outbox_event SET status = 'PUBLISHED' WHERE id = #{id} AND status = 'PENDING'")
    int markPublished(@Param("id") Long id);

    /**
     * 发布失败登记：原子累加 retry_count；当 retry_count+1 >= maxRetry 时置 FAILED，
     * 否则保持 PENDING 以便下一轮扫描重试。
     */
    @Update("UPDATE t_outbox_event SET retry_count = retry_count + 1, "
            + "status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 'FAILED' ELSE status END "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int recordPublishFailure(@Param("id") Long id, @Param("maxRetry") int maxRetry);

    /**
     * 消费确认回写（幂等）：仅 PUBLISHED/PENDING 可流转为 PROCESSED。
     * 重复投递（At-least-once 重放）时返回 0，不产生重复副作用。
     */
    @Update("UPDATE t_outbox_event SET status = 'PROCESSED' WHERE id = #{id} AND status IN ('PUBLISHED', 'PENDING')")
    int markProcessed(@Param("id") Long id);
}
