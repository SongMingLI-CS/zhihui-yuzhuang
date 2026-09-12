-- ============================================================
-- Flyway V4 · 账号安全与生命周期列（首次改密 / 停用 / 密码重置 / 登录风控）
--
-- 背景（对应审计 P0-2、阶段 A）：
--   * 演示账号曾以固定公开口令长期有效，缺少“首次登录强制改密”；
--   * 缺少停用/启用的时间与原因留痕，无法审计；
--   * 缺少密码重置时间与失败登录锁定字段。
--
-- 全部使用 ADD COLUMN IF NOT EXISTS，存量库多次执行安全；新列均有默认值/可空，
-- 不影响既有 99 个后端测试与历史数据。
-- ============================================================

ALTER TABLE t_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS password_updated_at  TIMESTAMP;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS last_login_at        TIMESTAMP;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS disabled_at          TIMESTAMP;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS disabled_reason      VARCHAR(255);
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS failed_login_count   INT         NOT NULL DEFAULT 0;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS locked_until         TIMESTAMP;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS created_by           VARCHAR(64);
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS updated_at           TIMESTAMP;

COMMENT ON COLUMN t_user.must_change_password IS '是否强制下次登录改密（演示账号/管理员重置后为 true）';
COMMENT ON COLUMN t_user.password_updated_at  IS '最近一次密码变更时间';
COMMENT ON COLUMN t_user.last_login_at        IS '最近一次成功登录时间';
COMMENT ON COLUMN t_user.disabled_at          IS '停用时间（停用时写入）';
COMMENT ON COLUMN t_user.disabled_reason      IS '停用原因（审计留痕）';
COMMENT ON COLUMN t_user.failed_login_count   IS '连续登录失败次数（成功后清零）';
COMMENT ON COLUMN t_user.locked_until         IS '锁定到期时间（连续失败触发，期间拒绝登录）';
COMMENT ON COLUMN t_user.created_by           IS '账号创建者（平台管理员用户名）';
COMMENT ON COLUMN t_user.updated_at           IS '最近更新时间';
