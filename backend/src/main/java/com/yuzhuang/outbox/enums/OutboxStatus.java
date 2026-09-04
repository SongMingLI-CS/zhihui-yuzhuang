package com.yuzhuang.outbox.enums;

/**
 * Outbox 发件箱事件生命周期状态。
 *
 * <ul>
 *   <li>{@link #PENDING}    —— 事务内落库、待投递；</li>
 *   <li>{@link #PUBLISHED}  —— 已成功写入 Redis Stream（投递侧确认）；</li>
 *   <li>{@link #PROCESSED}  —— 消费者处理完成并 ACK（消费侧状态闭环）；</li>
 *   <li>{@link #FAILED}     —— 重试达到上限，投递失败需告警介入。</li>
 * </ul>
 *
 * <p>状态以 {@link #name()} 写入 t_outbox_event.status（VARCHAR）。
 */
public enum OutboxStatus {

    /** 事务内已记录、待定时任务扫描投递 */
    PENDING("待投递"),

    /** 已成功发布到 Redis Stream（至少一次投递语义的"发布成功"确认点） */
    PUBLISHED("已发布到 Redis Stream"),

    /** 后台消费者已消费并回写处理完成 */
    PROCESSED("已处理完成"),

    /** 重试次数达到上限，投递失败（需监控告警） */
    FAILED("投递失败");

    private final String description;

    OutboxStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
