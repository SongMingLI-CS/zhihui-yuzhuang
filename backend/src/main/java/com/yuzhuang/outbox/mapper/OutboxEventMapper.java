package com.yuzhuang.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuang.outbox.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 发件箱 Mapper。
 *
 * <p>除 MyBatis-Plus BaseMapper CRUD 外，提供 Outbox 可靠投递所需的状态流转语句。
 * 发布端采用<b>租约认领</b>治理多实例重复 XADD：
 * <ol>
 *   <li>{@link #selectClaimableBatch} 只取「未认领或租约已过期」的 PENDING 事件；</li>
 *   <li>{@link #claimById} 条件 UPDATE 原子认领（并发下恰有一个实例抢到租约并负责 XADD）；</li>
 *   <li>发布成功 {@link #markPublished}、失败 {@link #recordPublishFailure}、
 *       消费完成 {@link #markProcessed} 均清空租约（成功终态 / 失败立即续投）。</li>
 * </ol>
 * 认领后实例崩溃：租约到期（lease_until）后由其他实例自动接管，事件不丢失。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 拉取一批可认领的待投递事件：status=PENDING、retry_count &lt; maxRetry、
     * 且「未认领」或「租约已过期」，按 id 升序取 limit 条。
     *
     * @param limit    批次上限
     * @param maxRetry 最大重试次数（超过不再拉取）
     * @param now      当前时刻（租约比较基准）
     * @return 可认领事件列表（可能为空）
     */
    @Select("SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, "
            + "claimed_at, lease_until, instance_id, created_at "
            + "FROM t_outbox_event "
            + "WHERE status = 'PENDING' AND retry_count < #{maxRetry} "
            + "  AND (claimed_at IS NULL OR lease_until IS NULL OR lease_until < #{now}) "
            + "ORDER BY id ASC "
            + "LIMIT #{limit}")
    List<OutboxEvent> selectClaimableBatch(@Param("limit") int limit,
                                           @Param("maxRetry") int maxRetry,
                                           @Param("now") LocalDateTime now);

    /**
     * 原子认领（发布租约）：仅当仍 PENDING 且「未认领或租约已过期」时生效，返回 1；
     * 并发/多实例竞争时只有一个调用方获得租约（其余返回 0 并跳过该事件）。
     *
     * @param id         事件主键
     * @param claimedAt  认领时刻
     * @param leaseUntil 租约到期时刻
     * @param instanceId 认领实例标识（诊断用）
     * @return 影响行数：1 认领成功；0 已被其他实例持有（未过期）或状态已流转
     */
    @Update("UPDATE t_outbox_event "
            + "SET claimed_at = #{claimedAt}, lease_until = #{leaseUntil}, instance_id = #{instanceId} "
            + "WHERE id = #{id} AND status = 'PENDING' "
            + "  AND (claimed_at IS NULL OR lease_until IS NULL OR lease_until < #{claimedAt})")
    int claimById(@Param("id") Long id,
                  @Param("claimedAt") LocalDateTime claimedAt,
                  @Param("leaseUntil") LocalDateTime leaseUntil,
                  @Param("instanceId") String instanceId);

    /**
     * 发布成功确认：仅当仍为 PENDING 时置为 PUBLISHED，并清空发布租约
     * （CAS 防止并发扫描重复覆盖状态）。
     */
    @Update("UPDATE t_outbox_event "
            + "SET status = 'PUBLISHED', claimed_at = NULL, lease_until = NULL, instance_id = NULL "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markPublished(@Param("id") Long id);

    /**
     * 发布失败登记：原子累加 retry_count 并清空租约（失败事件可被下一轮立即续投）；
     * 当 retry_count+1 &gt;= maxRetry 时置 FAILED，否则保持 PENDING。
     */
    @Update("UPDATE t_outbox_event "
            + "SET retry_count = retry_count + 1, claimed_at = NULL, lease_until = NULL, instance_id = NULL, "
            + "status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 'FAILED' ELSE status END "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int recordPublishFailure(@Param("id") Long id, @Param("maxRetry") int maxRetry);

    /**
     * 消费确认回写（幂等）：仅 PUBLISHED/PENDING 可流转为 PROCESSED，并清空租约。
     * 重复投递（At-least-once 重放）时返回 0，不产生重复副作用。
     */
    @Update("UPDATE t_outbox_event "
            + "SET status = 'PROCESSED', claimed_at = NULL, lease_until = NULL, instance_id = NULL "
            + "WHERE id = #{id} AND status IN ('PUBLISHED', 'PENDING')")
    int markProcessed(@Param("id") Long id);
}
