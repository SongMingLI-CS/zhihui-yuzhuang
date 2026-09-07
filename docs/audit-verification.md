# 智汇于庄 · 项目完善度审查 事实核查与整改台账

> 状态：阶段0+阶段1 整改执行中（认证强制化 + JWT↔租户绑定 + 商品写跨租户修复）
> 核查基准：仓库 HEAD（`d125800`）。本文档为画布（Project Review Canvas）论断的**证据化对照表**，并记录本切片的设计决策与残留风险。

## 一、画布论断核查对照

| 画布论断 | 判定 | 证据（相对路径） | 整改动作 |
|---|---|---|---|
| JWT 匿名放行、写接口无角色校验 | ✅ 属实 | `backend/.../auth/filter/JwtAuthenticationFilter.java`：注释明示「不强制拦截」，无效令牌按匿名继续；全仓无 Spring Security/`@PreAuthorize`；`AuthContext` 生产代码零消费方 | 本切片：`AuthGuardInterceptor` 强制 + RBAC 矩阵 |
| X-Tenant-Id 可伪造、未与 JWT 绑定 | ✅ 属实 | `backend/.../web/filter/TenantContextFilter.java` 直接信头；`AuthPrincipal.tenantId` 自 JWT 解析后从不参与鉴权 | 本切片：受保护端点租户唯一取 JWT `tenantId`，header 不再作为租户源 |
| 商品更新/上下架存在跨租户面 | ✅ 属实 | `ProductAdminServiceImpl.updateProduct/updateStatus` 走 `selectById`+`updateById`，无租户/版本条件 | 本切片：service 增加租户形参与作用域校验（本租户 ∪ global/VILLAGE） |
| 支付/关单/库存释放闭环缺失 | ✅ 属实（措辞纠偏：实际为**跳过支付直接 STOCK_CONFIRMED**，非停留在 PENDING_PAY） | `OrderServiceImpl` 下单即 STOCK_CONFIRMED；历史无支付/关单 job/退款代码 | 阶段2-B 已闭环：沙箱支付（STOCK_CONFIRMED→PROCESSING）+ 超时关单 + 幂等库存回补；真实通道待接入 |
| 无 Flyway、schema.sql 初始化 | ✅ 属实 | 历史 `deploy/docker-compose.yml` 用 SPRING_SQL_INIT 每次启动执行 `db/schema.sql`+`seed-data.sql` | 阶段2-A 已闭环：Flyway（flyway-core/postgres 模块）+ V1 基线 + V2 支付列迁移 + baseline-on-migrate + compose 接入 |
| H5 消费者闭环不完整 | ✅ 属实 | `h5/src/App.tsx` 仅浏览+下单+问答；`h5/src/lib/http.ts` 无订单查询 | 阶段3（待立项） |
| 移动协同端为空 | ✅ 属实 | `mobile/` 仅 README | 阶段3+（待立项） |
| 大屏=demo 数据 | ✅ 属实 | `web/src/app/dashboard/page.tsx` import `@/lib/demo` 并带演示徽标 | 阶段3：真实聚合 API（待立项） |
| 知识库静态 | ✅ 属实 | `web/src/app/knowledge/page.tsx` 文档清单来自 demo | 阶段3（待立项） |
| AI 审批无后端事实 | ✅ 属实 | `web/src/app/agents/page.tsx` approved 为本地 state，「模拟同步/模拟推送」仅前端 | 阶段3（待立项） |
| 高并发叙事与实现偏差 | ✅ 属实 | `OrderServiceImpl`：checkout 为同步事务 CAS→落库→outbox，Redis Streams 仅事件投递 | 文档侧已纠正；后续立项：网关削峰/队列化 |
| Outbox 多实例发布竞争 | ✅ 属实（存在缓解） | `OutboxSweeper.selectPendingBatch` 无 SKIP LOCKED/claim；消费端以 outbox `PROCESSED` 幂等回写 | 阶段3：claim/lease + SKIP LOCKED（待立项） |
| 端口/配置文档漂移 5432 vs 5433 | ✅ 属实 | compose 默认宿主映射 `5433→容器5432`，而 `README.md` 表格与根 `.env.example` 写作 5432；`ai-service` 默认 `DATABASE_URL` 也指向 5432 | 本切片 B0.3 修正根文档；ai-service 模块内文档归其模块后续修正 |
| 其余 P1/P2（AI 评测门槛、AI 输入治理、缺压测、商品域简化、物流未建模等） | ✅ 与代码面一致 | 逐项均有对应缺口 | 待立项 |

## 二、本切片设计决策（D1–D4）

| # | 决策 | 方案 | 说明 |
|---|---|---|---|
| D1 | 认证实现 | 轻量 `HandlerInterceptor`（`AuthGuardInterceptor`）+ `AuthContext`，不引入 Spring Security | 与现有栈匹配、可单测；Spring Security 留待生产化评估 |
| D2 | RBAC（最小化） | VILLAGE/COOPERATIVE 可写商品与履约；FARMER 仅本租户订单只读 | 不扩角色枚举、不引入 AOP |
| D3 | 受保护端点租户来源 | 唯一取 JWT `tenantId`，`X-Tenant-Id` 头在受保护端点弃用 | header 仅保留给公开浏览/下单语义 |
| D4 | 匿名边界 | allowlist 见 §三 | H5 免登录下单/浏览不受影响 |

## 三、认证/授权矩阵（= B1 实现事实源）

### 3.1 匿名公开（无需令牌）
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/v1/auth/login` | POST | 换取 JWT |
| `/api/v1/healthz` | GET | 健康探针 |
| `/api/v1/products` | GET | 商品列表（本租户 ∪ global 在售） |
| `/api/v1/products/{id}` | GET | 商品详情 |
| `/api/v1/orders/checkout` | POST | C 端免登录下单（X-Tenant-Id=卖货店铺租户语义保留） |

### 3.2 任意已认证（数据域=JWT tenantId）
| 端点 | 方法 |
|---|---|
| `/api/v1/orders` | GET |
| `/api/v1/orders/{orderNo}` | GET |

### 3.3 COOPERATIVE / VILLAGE（数据域=JWT tenantId）
| 端点 | 方法 |
|---|---|
| `/api/v1/products` | POST |
| `/api/v1/products/{id}` | PUT |
| `/api/v1/products/{id}/status` | PATCH |
| `/api/v1/orders/{orderNo}/ship` `/mark-ready` `/recover` | POST |
| `/api/v1/orders/{orderNo}/pay/sandbox`（沙箱支付演示） | POST |

### 3.4 商品写作用域规则
- 目标商品 `tenant_id` 必须 = 令牌 `tenantId`；跨租户一律映射 **404 + A1004**（避免暴露资源存在性）。
- `global`（共享目录）仅 **VILLAGE（村委/运营）** 可维护；COOPERATIVE/FARMER 无此权限。

## 四、契约改动（api-spec 同步项）
1. 受保护端点新增 `security: [{BearerAuth: []}]` 标注。
2. `/orders` 系列与 `/products` 写的 `X-Tenant-Id` 参数**移除**（改由 JWT tenantId 提供）。
3. `/orders/checkout`、`GET /products` 保留 `X-Tenant-Id`（公开语义）。
4. 错误面：未认证 → **401 + A1002**；无权限 → **403 + A1003**；跨租户/不存在 → **404 + A1004**（复用既有 ResultCode，无新增码）。
5. 阶段2 新增沙箱支付端点 `/orders/{orderNo}/pay/sandbox`（COOPERATIVE/VILLAGE，STOCK_CONFIRMED→PROCESSING）；`t_order` 支付/关单列由 Flyway V1（全新建库）/V2（存量库补齐）管理。

## 五、残留风险（本切片范围外，需后续立项）
- 真实支付通道化：验签/回调/退款/对账/金额二次校验、沙箱→真实渠道 Adapter（设计见 `docs/payment-closeout-design.md`）。
- 手动取消（MANUAL_CANCEL）接口与「开始拣货 PENDING→PICKING」入口待补（与 M 端协同联动）。
- C 端下单仍以 header 表达店铺租户（无消费者身份）；未来接入登录/限流后收敛。
- Outbox 发布竞争窗口未关闭（阶段3：claim/lease + SKIP LOCKED）。
- AI 端点无身份/限流治理；AI 评测缺门槛基线。
- 数据库迁移与生产部署动作需在部署环境执行（本机 Docker 5432/8080 被占用，无法本地跑 go-live）。

## 六、实现与验证状态

### 6.1 提交记录（本切片）
| 提交 | 内容 |
|---|---|
| `af95c6f` | docs：核查台账 + api-spec 鉴权标注（BearerAuth/受保护端点）+ 根文档 5433 对齐 |
| `46d38ae` | backend：`AuthGuardInterceptor` + `AuthContext.require()` + JWT 租户绑定 + 商品写跨租户修复（含 global 目录 VILLAGE 规则） |
| `079e308` | backend tests：WebContractTest/ProductAdminTest/OrderQuery/Fulfillment 对抗与回归；全量 **86/86 绿**（H2 test profile） |
| `ab5cc11` | web：登录租户锁定 + 顶栏选择器登录态禁用 + 401 自动回登录 + lint 修复 |
| `待提交（阶段2-A）` | backend：Flyway 依赖/配置/关闭（test H2 关闭）+ V1 基线 + V2 支付列迁移 + compose/seed 接入 |
| `待提交（阶段2-B）` | backend：支付沙箱 + 超时关单调度 + 幂等库存回补 + 相关测试（94/94 绿） |
| `待提交（docs2）` | docs：api-spec 沙箱支付端点 + payment-closeout-design + 台账更新 |

### 6.2 验证命令与结果
- `cd backend && mvn test`：全量 **Tests run 94, Failures 0, Errors 0**（H2 test profile，含支付/关单对抗用例）。
- Flyway：container/dev 启动自动迁移；存量库 baseline-on-migrate；测试上下文 `spring.flyway.enabled=false` 保持 H2 自举。
- `cd web && npm run typecheck`：通过；`npm run lint`：No warnings or errors。
- h5 / ai-service：阶段1-2 未改其代码（仅 ai-service 模块内端口文档遗留，归其模块后续修正）。

### 6.3 残留风险（后续立项跟踪）
- 真实支付通道化与退款/对账（沙箱→真实 Adapter；设计见 payment-closeout-design.md）。
- 手动取消接口、PENDING→PICKING 开始拣货入口（M 端协同联动）。
- C 端下单仍以 header 表达店铺租户（匿名无身份，接入消费者登录后收敛）。
- Outbox claim/SKIP LOCKED、AI 端点治理与评测门槛、dashboard/knowledge demo 数据收敛（阶段3）。
- 商品 `version` 列存在但未启用 `@Version` 全量 OCC；已有加载+作用域+条件更新兜底，完整 OCC 与后续优化一并推进。
- ai-service 模块内 `DATABASE_URL`/文档的 5432 表述与宿主机 5433 对齐，留待 ai-service 模块维护时处理（避免越模块改动）。

