# 支付/超时关单/库存回补 设计文档

> 状态：已实现「演示沙箱 + 超时关单 + 幂等库存回补」；真实支付通道仅预留 Adapter 语义（未接资金）。
> 关联契约：`docs/api-spec.yaml` `/orders/{orderNo}/pay/sandbox`；数据库迁移 `V2__add_order_payment_closure_columns.sql`。

## 1. 问题背景（画布 P0）
历史行为：checkout CAS 扣库存后**立即以 STOCK_CONFIRMED 落库并返回 30 分钟 expireTime**，但全仓没有任何支付回调/取消/退款/关单/库存回补逻辑。后果：
- 30 分钟不支付的订单永久占用预留库存（资损/超卖风险随时间累积）；
- 「支付」语义缺失，订单无法从 STOCK_CONFIRMED 前进到 PROCESSING（履约域）。

## 2. 目标状态机（订单交易轴）
```text
PENDING_PAY ──(可选历史/占位)─────────────┐
                                          ▼
H5 checkout(CAS扣库存) ──> STOCK_CONFIRMED ──支付成功──> PROCESSING ──履约──> COMPLETED
                              │  ▲                           │
                              │  │                           │
                              │  └── 超时(默认30min)          │
                              └─────> CANCELLED(PAY_TIMEOUT)  │
                                        ▲                     │
                                        └──── 手动取消 ────────┘
```
- **STOCK_CONFIRMED = 库存已锁定、等待支付**（下单即锁定，防超卖）；
- **支付成功** → 记录 `pay_channel / pay_trade_no / paid_at`，交易状态 → PROCESSING（进入履约轴）；
- **超时未支付**（createdAt 早于 当前-30min 且 paid_at IS NULL）→ CANCELLED，`close_reason='PAY_TIMEOUT'`，并**按明细回补库存**；
- 履约轴（PENDING→PICKING→READY→SHIPPED）独立推进，本设计不改变其语义。

## 3. 一致性设计（核心）
### 3.1 关单 = 条件状态门（并发/多实例安全）
只允许一次成功：
```sql
UPDATE t_order
   SET status='CANCELLED', cancelled_at=now(), close_reason='PAY_TIMEOUT'
 WHERE tenant_id=? AND order_no=?
   AND status='STOCK_CONFIRMED' AND paid_at IS NULL;   -- 影响行数 0 → 跳过
```
- 恰好 1 个线程影响 1 行并负责回补库存与写 Outbox；其余线程影响 0 行 → 直接跳过。
- 因此**回补天然幂等**，无需独立幂等表（多实例部署同 DB 亦安全）。
- 关单、库存回补、Outbox(`ORDER_CANCELLED`)同处一个事务。

### 3.2 库存回补（restoreStock）
```sql
UPDATE t_product_sku SET stock = stock + #{qty}, version = version + 1 WHERE id = ?
```
由 3.1 的状态门保证「只回补一次」；支付成功后订单进入 PROCESSING，**不再**被关单任务命中。

### 3.3 支付入账（沙箱）与幂等
`POST /orders/{orderNo}/pay/sandbox`（需 COOPERATIVE/VILLAGE JWT）：
```sql
UPDATE t_order SET status='PROCESSING', pay_channel=?, pay_trade_no=?, paid_at=now()
 WHERE tenant_id=? AND order_no=? AND status='STOCK_CONFIRMED' AND paid_at IS NULL;
```
- 影响 0 行时的分支：同渠道同 tradeNo 已支付 → 幂等回放成功；异流水号已支付 → 409 B2003 拒绝；CANCELLED → 404。
- 写 Outbox(`ORDER_PAID`)。
- 真实通道接入点：Adapter 将渠道回调验签后调用 `OrderPaymentClosureService.sandboxPay`（该方法即统一入账入口，仅需把 channel 换成真实渠道枚举）。

## 4. 定时任务
- `OrderCloseScheduler`：`@Scheduled(fixedDelay)`，每 60s（可配）扫一批（默认 50），由 `yuzhuang.order-close.enabled` 控制。
- 批次内逐单条件状态门，无锁表；单轮失败仅日志，下轮继续。

## 5. 数据变更（Flyway）
- `t_order` 新增 5 个可空列：`pay_channel VARCHAR(32)` / `pay_trade_no VARCHAR(64)` / `paid_at TIMESTAMP` / `cancelled_at TIMESTAMP` / `close_reason VARCHAR(32)`。
- 全新建库由 V1（基线含列）+V2（IF NOT EXISTS 幂等跳过）；存量库经 baseline-on-migrate 打基线 V1 后由 V2 ALTER 补齐。
- 测试 H2（schema-test.sql）同步加入同名字段。

## 6. 残留风险与后续（真实通道化）
- 真实支付需：渠道签名验签、支付回调公网网关、退款/对账定时、金额二次校验（沙箱未校验 amount）。
- 「开始拣货（PENDING→PICKING）」入口尚未存在（与 M 端扫码发货同批立项），故支付后订单停留在 PROCESSING/PENDING 属预期待接续。
- 手动取消（MANUAL_CANCEL）与关单复用同一回补路径，接口待后续补。
- Web B 端「模拟支付成功」按钮可按本契约接入（`/orders/{orderNo}/pay/sandbox`），属 UI 阶段工作。
