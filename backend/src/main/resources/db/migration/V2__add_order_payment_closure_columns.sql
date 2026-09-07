-- ============================================================
-- Flyway V2 · 订单支付/关单闭环列
--
-- 适用场景：
--  * 存量库（Flyway baseline V1 后）：补齐 5 个可空列；
--  * 全新建库：V1 已包含下列列，ADD COLUMN IF NOT EXISTS 幂等跳过。
-- 幂等：重复执行不会报错。
-- ============================================================

ALTER TABLE t_order ADD COLUMN IF NOT EXISTS pay_channel  VARCHAR(32);
ALTER TABLE t_order ADD COLUMN IF NOT EXISTS pay_trade_no VARCHAR(64);
ALTER TABLE t_order ADD COLUMN IF NOT EXISTS paid_at      TIMESTAMP;
ALTER TABLE t_order ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;
ALTER TABLE t_order ADD COLUMN IF NOT EXISTS close_reason VARCHAR(32);

COMMENT ON COLUMN t_order.pay_channel  IS '支付渠道（沙箱 SANDBOX / 后续真实渠道适配）';
COMMENT ON COLUMN t_order.pay_trade_no IS '渠道支付流水号（支付幂等去重键）';
COMMENT ON COLUMN t_order.paid_at      IS '支付成功时间';
COMMENT ON COLUMN t_order.cancelled_at IS '取消/超时关单时间';
COMMENT ON COLUMN t_order.close_reason IS '关闭原因：PAY_TIMEOUT/MANUAL_CANCEL 等';
