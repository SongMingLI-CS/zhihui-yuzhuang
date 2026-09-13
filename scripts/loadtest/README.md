# 压测脚本（k6）

> 目标：让「下单幂等 / 防超卖 / 限流」可重复验证，并且**只陈述实测结果**。
> 本项目未在开发机跑过压测，因此文档中**不提供任何吞吐量数字**。

## 前置

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
# 确认商品可见（决定用哪个 SKU_ID）
curl -s "http://localhost/api/v1/products" -H "X-Tenant-Id: tenant_yuzhuang_001" | head
```

## 运行

```bash
# 需要先安装 k6：https://k6.io/docs/get-started/installation/
k6 run -e BASE_URL=http://localhost -e SKU_ID=1001 -e STOCK=50 \
       -e RUN_ID=$(date +%s) scripts/loadtest/checkout.js
```

参数：

| 变量 | 含义 | 默认 |
|---|---|---|
| `BASE_URL` | 网关地址 | `http://localhost` |
| `SKU_ID` | 被抢购的 SKU 主键（取 `/api/v1/products` 的 `id`） | `1001` |
| `STOCK` | 该 SKU 当前库存（用于防超卖判定） | `50` |
| `TENANT_ID` | 下单租户 | `tenant_yuzhuang_001` |
| `RUN_ID` | 幂等键前缀（避免跨轮复用） | `run1` |

## 判定标准

1. **幂等**：同一 `X-Idempotency-Key` 的第二次请求不得因为「重复扣库存」而返回 `B2001`（应幂等回放）。
2. **防超卖**：`下单成功数 ≤ STOCK`；若 `成功数 > STOCK` 则脚本在 summary 中标记 ❌。
3. **限流**：登录端点高频请求应出现 `429`（网关 `limit_req` 生效）；若全为 200/401 而无 429，说明网关限流未生效。

> 注意：压测会真实写入订单与扣减库存。请在**演示/测试环境**执行，跑完后可用
> `scripts/restore_db.sh` 恢复，或在 k6 结束后按需取消测试订单。
