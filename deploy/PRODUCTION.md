# 智汇于庄 · 生产部署手册（阶段 A 交付物）

> 面向运维/DBA。演示环境请用 `deploy/docker-compose.yml`；本文件只讲生产。

## 1. 生产与演示的硬边界

| 维度 | 演示（demo） | 生产（production） |
|---|---|---|
| 编排文件 | `deploy/docker-compose.yml` | `deploy/docker-compose.prod.yml` |
| 环境标识 | `APP_ENV=demo` / `DEMO_MODE=true` | `APP_ENV=production` / `DEMO_MODE=false` |
| 演示账号 | 启动自举 `admin/coop001/farmer001/gov001/platform001` | **不创建任何账号** |
| 演示灌数 | `SPRING_SQL_INIT_DATA_LOCATIONS` 幂等种子 | 禁用；仅 Flyway 受控迁移 |
| JWT 密钥 | 开发默认值可用 | 必须高熵（≥32 字符），否则启动失败 |
| 数据库端口 | 映射宿主 5433 | **不映射**（仅容器网络） |
| Redis 端口 | 映射宿主 6379 | **不映射** |
| 网关 | 仅 80 | 80（301）+ 443（TLS） |

`ProductionSafetyValidator` 会在启动阶段拒绝以下配置：`DEMO_MODE=true`、激活 `demo/dev/test` profile、
`spring.sql.init.mode=always`、JWT 密钥为空/过短/等于开发默认值、数据库口令为开发默认 `rural_password`。

## 2. 部署步骤

```bash
# 1) 准备环境变量（切勿提交仓库）
cp .env.production.example .env.production
#   - SERVER_NAME / TLS_DIR / POSTGRES_PASSWORD / AUTH_JWT_SECRET 必填
#   - AUTH_JWT_SECRET 生成：openssl rand -base64 48

# 2) 证书（Let's Encrypt 示例）
sudo certbot certonly --standalone -d example.gov.cn
#   TLS_DIR=/etc/letsencrypt/live/example.gov.cn（含 fullchain.pem / privkey.pem）

# 3) 启动
docker compose --env-file .env.production -f deploy/docker-compose.prod.yml up -d --build

# 4) 校验
curl -fsS https://example.gov.cn/healthz                     # ok
curl -fsS https://example.gov.cn/api/v1/healthz              # code=00000
curl -fsS https://example.gov.cn/ai/v1/healthz               # code=00000
docker compose -f deploy/docker-compose.prod.yml ps          # 全部 healthy
```

首次上线后，由平台管理员账号线下创建（避免公网自举）：

```bash
# 通过受控 SQL/一次性脚本创建首个 PLATFORM_ADMIN（口令哈希用 PasswordEncoder 生成）
# 之后登录 /b/ 平台管理页创建租户与政府/商家账号，并授予政府区域范围。
```

## 3. 生产安全要点（已实现）

- **Nginx**：TLS1.2+、HSTS、CSP、X-Frame-Options、nosniff、Referrer-Policy、server_tokens off；
  登录 5r/m、下单 30r/m、AI 20r/m、上传 10r/m 限流；body 限制按端点区分（登录 64k / 下单 256k / 上传 12m）。
- **API 文档**：`/swagger-ui`、`/v3/api-docs`、`/ai/v1/docs` 仅内网网段可访问（10/8、172.16/12、192.168/16、127.0.0.1）。
- **会话**：B 端使用 `HttpOnly + Secure + SameSite` Cookie（`yz_session`），写接口走 CSRF 双提交（`yz_csrf` + `X-CSRF-Token`）；
  令牌仅存内存，不再落 localStorage。
- **令牌**：生产 `AUTH_JWT_SECRET` 高熵校验 + 启动 fail-fast；连续 5 次登录失败锁定 15 分钟。
- **首登改密**：演示账号与管理员重置账号均 `must_change_password=true`，前端强制跳转改密页。

## 4. 备份与恢复

```bash
# 备份（自定义格式 + gzip，保留最近 14 份）
bash scripts/backup_db.sh ./backups deploy/docker-compose.prod.yml

# 恢复（会覆盖现有库；先停业务服务）
RESTORE_YES=1 bash scripts/restore_db.sh ./backups/yuzhuang-*.dump.gz deploy/docker-compose.prod.yml
```

建议：每日全量备份 + 保留 14 天；上线前/迁移前手动备份一次；每季度做一次恢复演练并记录 RTO。

## 5. 数据库迁移与回滚

- 迁移：`backend/src/main/resources/db/migration/V*.sql`，Flyway 启动自动执行，`baseline-on-migrate=true` 兼容存量库。
- 当前版本链：V1 基线 → V2 订单支付/关单列 → V3 Outbox 租约列 → V4 账号安全列 → V5 政府授权范围/审计/租户类型。
- 回滚策略（无自动 down 迁移）：
  1. 应用回滚：`docker compose ... up -d --build backend ai-service` 指定上一镜像 tag；
  2. 数据回滚：使用 `scripts/restore_db.sh` 恢复迁移前备份；
  3. 迁移脚本均为**向后兼容新增**（ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS），旧版本应用可安全共存于新 schema。

## 6. 仍需外部开通的事项（代码侧已完成适配/校验，未开通不得宣称可用）

| 事项 | 状态 | 说明 |
|---|---|---|
| 域名 + TLS 证书 | 待用户提供 | 需 DNS 控制权；模板已参数化 |
| 微信/支付宝商户号与 v3 密钥 | 待用户提供 | 生产未配置时支付禁用（不模拟成功） |
| 短信通道 | 待用户提供 | 验证码/通知未启用 |
| 对象存储（S3/COS/OSS） | 待用户提供 | 商品图片上传在未配置时不可用 |
| DeepSeek / Embedding 密钥 | 待用户提供 | 未配置时 AI readiness 标记不可用，不返回伪装结果 |
| 抖音/快手开放平台凭证 | 待用户提供 | 渠道适配器为 disabled，不得宣传已接入 |
