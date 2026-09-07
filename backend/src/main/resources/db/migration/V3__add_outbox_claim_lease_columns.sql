-- ============================================================
-- Flyway V3 · Outbox 投递租约列（多实例发布竞争治理）
--
-- 背景：OutboxSweeper 多实例并发部署时，若两个实例同时 selectPendingBatch，
-- 会对同一事件重复 XADD（At-least-once 重复投递）。引入发布端租约：
--   * claimed_at / lease_until / instance_id：实例以条件 UPDATE 认领后再投递，
--     未获认领者跳过；认领后崩溃/失联由 lease_until 到期后自动回收。
--   * 发布成功/失败/消费完成均清空租约（成功流转 PUBLISHED/PROCESSED，
--     失败保持 PENDING 供下一轮续投）。
-- 幂等：ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS，可重复执行。
-- ============================================================

ALTER TABLE t_outbox_event ADD COLUMN IF NOT EXISTS claimed_at  TIMESTAMP;
ALTER TABLE t_outbox_event ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
ALTER TABLE t_outbox_event ADD COLUMN IF NOT EXISTS instance_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_t_outbox_claimable
    ON t_outbox_event (status, lease_until);

COMMENT ON COLUMN t_outbox_event.claimed_at  IS '认领时间（发布端租约）';
COMMENT ON COLUMN t_outbox_event.lease_until IS '租约到期时间：到期后其他实例可重新认领（崩溃恢复）';
COMMENT ON COLUMN t_outbox_event.instance_id IS '持有租约的发布实例标识（诊断用）';
