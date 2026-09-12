# 开发路线图 · 智汇于庄

> 本路线图按"先底座、再闭环、后亮眼"的顺序推进。文档以当前代码基线（main @ 19fca39，2026-09-06）为准，
> 阶段状态与真实实现逐项对齐：已交付项勾选 `[x]`，部分完成项拆分为 `[x]` 子项 + `[ ]` 缺口子项，
> 缺口子项即后续待办清单，避免代码与文档不一致导致重复实现或漏补。
> 变更约定：每实现一项立即勾选回填；代码与文档冲突时以代码为准并回填本文档；接口契约变更须同步 `docs/api-spec.yaml`。

## 2026-09-12 整改批次（已落地）

- ✅ 阶段 A：`APP_ENV`/`DEMO_MODE` 边界、生产启动 fail-fast、账号安全字段与首登改密、Cookie 会话 + CSRF、Nginx 生产模板、Compose 生产编排、备份/恢复脚本。
- ✅ 阶段 B：`PLATFORM_ADMIN`/`GOVERNMENT` 角色、`t_gov_scope` 授权范围、端点级权限策略（deny-by-default）、平台管理 API + 最小 UI、审计日志、AI 侧 JWT 鉴权。
- ◐ 阶段 C/D 核心：商家商品全状态分页（修复下架不可见）、政府只读聚合（口径 + 快照 + 脱敏）、`/b/merchant`、`/b/gov`、`/b/platform` 页面。
- ○ 待办：商品媒体/对象存储、政府导出与真实事件流、支付 Provider 与订单中心、AI readiness/审批持久化、移动端与渠道适配器、Playwright E2E 与压测。

## 阶段总览

```text
阶段 0  项目骨架与基础设施        ✅ 已完成
阶段 1  最小业务闭环（单渠道下单）  ◐ 基本完成（缺认证/用户体系、商品写接口、订单查询）
阶段 2  高并发工程能力（削峰/防超卖）◐ 核心已完成（缺压测脚本、渠道适配器骨架）
阶段 3  AI 能力（RAG + 多智能体）  ◐ 大部分完成（缺关键词召回/重排、PDF解析、ASR、审批接口）
阶段 4  M 端与 C 端接入、数据大屏  ◐ 进行中（当前阶段：h5/web 已交付，M 端 Flutter 未开工）
阶段 5  公域渠道与答辩打磨        ○ 未开始
```

---

## 阶段 0 · 项目骨架与基础设施

**目标：** 仓库结构清晰、基础设施可一键拉起、文档齐备。

- [x] 重写 README（三端鼎立 + 工业级底座愿景）
- [x] 总体架构文档 `docs/architecture.md`
- [x] 骨架目录（backend / ai-service / web / h5 / mobile）
- [x] Docker Compose（PostgreSQL pgvector + Redis + Nginx 网关）
- [x] 根级 `.env.example` / `.gitignore` / `Makefile`
- [x] 初始化 git 仓库并建立首次提交基线

> 注：基础设施已由最初的"三件套"扩展为 6 服务编排（postgres / redis / gateway / backend / ai-service / h5 / web），
> 网关四路由 `/api` `/ai` `/b` `/h5` 均已接入 `rural-network`（见 `deploy/docker-compose.yml` 与 `deploy/nginx/nginx.conf`）。

**完成标准：** `make infra-up` 后三件套健康运行；目录/文档可作为团队协作基线。✅ 已达（并超出）

---

## 阶段 1 · 最小业务闭环（backend + B 端最小可用）

**目标：** 打通"商品上架 → 下单 → 扣库存 → 订单查询"的最小闭环。

- [x] `backend/` Spring Boot 工程骨架（单模块、统一响应 `ApiResponse`、全局异常 `GlobalExceptionHandler`、链路/租户 Filter、健康探针）
  - [ ] 多模块拆分（演进项，非阻塞，当前按单模块继续演进）
- [x] 领域模型建表：商品/SKU（含库存 `stock`/`version`）、订单 `t_order`、订单明细 `t_order_item`、发件箱 `t_outbox_event`（`schema.sql`，PG + H2 兼容）
  - [x] 租户元数据表 `t_tenant`（名称/属地/状态）
  - [x] 用户/账号/角色权限表 `t_user`（农户/合作社/村委，PBKDF2 密码哈希）
- [x] 商品读接口 `GET /api/v1/products`（租户在售 ∪ 全局共享）、`GET /api/v1/products/{id}`；库存种子初始化（`seed-data.sql` 幂等 + `deploy/seed-realistic-data.sql`）
  - [x] 商品管理写接口（上架/编辑/下架 SKU）+ B 端商品管理页（`/products`）
- [x] 下单接口 `POST /api/v1/orders/checkout`（事务内扣库存，已含 CAS + 幂等 + Outbox，见阶段 2）
- [x] 租户上下文（`X-Tenant-Id` → `TenantContextFilter`/`TenantContext` → MDC）
  - [x] JWT 认证 + 登录接口（`POST /api/v1/auth/login`，HS256 手写 JWT + 认证 Filter + PBKDF2 哈希，演示账号 admin/coop001/farmer001）
  - [ ] MyBatis-Plus 自动行级租户隔离（现为手工传 `tenant_id`，未引入 `TenantLineInnerInterceptor`）
- [x] `web/` B 端：订单列表/履约看板（响应式；订单页已接真实 `GET /orders` 与出库接口，看板聚合数字仍用 `demo.ts` 演示）
  - [x] B 端登录页 + 认证态（`/login`，顶栏用户区 + 退出，Bearer Token 持久化）
  - [ ] B 端商品管理页
  - [x] 后端订单列表/详情聚合查询接口（`GET /orders` + `GET /orders/{orderNo}`，含分页/状态/渠道过滤与租户隔离）
  - [x] B 端前端接入真实订单列表/履约操作（订单页已服务端分页/搜索 + 双状态徽标；支持 ship / mark-ready / recover 三个行内操作，队列可自循环）
    - [x] 状态域对齐：新增 `FulfillmentStatus`（PENDING/PICKING/READY/SHIPPED/ABNORMAL）独立于交易 `OrderStatus`（PENDING_PAY/STOCK_CONFIRMED/PROCESSING/COMPLETED/CANCELLED），`t_order` 新增 `fulfillment_status` 列，`status`/`fulfillment_status` 双轴解耦
    - [x] `GET /orders` 支持 `fulfillmentStatus`/`keyword`（订单号/收货人模糊）过滤；履约状态机三端点：`ship`(READY→SHIPPED)、`mark-ready`(PICKING→READY)、`recover`(ABNORMAL→PICKING)，状态不符/重复推进 409+B2003，不存在/跨租户 404+A1004
    - [x] `OrderFulfillmentTest` 12 用例（出库/标记待出库/异常恢复/404/跨租户/重复推进/履约与 keyword 过滤）与 `OrderQueryTest` 12 用例全通过；`web` typecheck + next build 通过
    - [x] `deploy/seed-realistic-data.sql` 交易状态收敛到合法 5 态，并新增 `fulfillment_status` 分布（PENDING→PICKING→READY→SHIPPED + ABNORMAL 样例）
    - [ ] 存量 PG 升级提示：先 `ALTER TABLE t_order ADD COLUMN fulfillment_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';`（已初始化容器需执行后重跑 `make seed`）
- [x] OpenAPI 文档（Springdoc + `docs/api-spec.yaml`，当前契约覆盖 `/orders/checkout`、`/qa/ask`）

**完成标准：** Postman 可完成 用户登录 → 创建商品 → 下单 → 扣减库存的闭环。◐ 部分达成（下单/扣库存闭环已通，登录与商品创建缺失）

---

## 阶段 2 · 高并发工程能力

**目标：** 支撑直播带货洪峰，解决超卖与可靠性问题。

- [x] Redis 接入（spring-data-redis / Lettuce，惰性连接；`OUTBOX_ENABLED=false` 时自动降级不阻断启动）
  - [ ] 商品/库存读缓存（Cache Aside）
  - [ ] 分布式锁（抢购/防击穿）
  - [ ] 会话缓存
- [x] Redis Streams：订单事件可靠投递链（`Outbox → Stream → OrderEventStreamConsumer` 消费 `ORDER_CREATED`）
- [x] CAS 乐观锁扣库存（`UPDATE ... WHERE id=? AND stock>=?`，`version` 自增审计；`OrderCheckoutConcurrencyTest` 并发验证无超卖）
- [x] Outbox 发件箱：订单事件与业务同事务落库，`OutboxSweeper` 后台可靠投递 + 重试上限告警（`OutboxRedisStreamIntegrationTest`）
- [x] 幂等与渠道流水号去重（`X-Idempotency-Key` + `(tenant_id, idempotency_key)` 唯一约束；冲突 B2002 回放既有订单）
- [ ] 压测脚本（`wrk`/`JMeter`）与调优（当前以 JUnit 并发测试代替，答辩压测曲线后补）
- [x] 渠道抽象 `OrderSource` 枚举（H5_PRIVATE / DOUYIN / KUAISHOU / B2B_PORTAL）
  - [ ] `ChannelAdapter` 接口 + 各渠道适配器骨架

> 注（现状语义，勿误改造）：Redis Streams 现为**订单事件可靠投递链**，下单仍为同步事务落库；
> **并非**原条目设想的"下单异步落库削峰"。请保持该语义（否则破坏幂等/契约/既有测试）。
> B 端大屏"实时流水"为前端按 Redis Stream 字段的客户端模拟，后端未暴露 SSE/WS 订阅端点。

**完成标准：** 压测下无超卖、无重复单；演示"瞬时洪峰 → 平滑落库"的大屏/日志曲线。◐ 部分达成（无超卖/无重复单已由并发测试证明，压测脚本与实时曲线未做）

---

## 阶段 3 · AI 能力（ai-service）

**目标：** RAG 防幻觉问答 + 多智能体营销内容生成。

- [x] `ai-service/` FastAPI 工程骨架与配置（lifespan 连接池、请求链路 ID、离线 Mock 降级）
- [x] pgvector 启用 + 知识库表 `t_knowledge_chunk`（`init_tables.py`）
- [x] 文本切片 → Embedding 向量化 → 入库管线（`ingest_docs.py` / `scripts/seed_rag.sh`，幂等）
  - [ ] PDF 解析器（当前 `data/raw_docs` 为 `.txt` 属地文档，PDF → 结构化切片未落地）
- [x] 向量召回（pgvector 余弦相似度 + 租户回退 + 分类二次过滤 + 阈值/ top_k 截断）
  - [x] 关键词召回（中文 2-gram + ILIKE 匹配）与双路融合重排（RRF，`search_hybrid`）
- [x] DeepSeek 接入 + 强约束防幻觉 Prompt + 引用溯源（无 Key 时离线 Mock 模板兜底）
- [ ] 语音转写接口（预留 ASR 适配）
- [x] 多智能体编排（Trend→Copywriter→Compliance；`POST /ai/v1/marketing/generate` 真实调用）
- [x] 营销合规质检（广告法违禁词命中 + 整改建议）
  - [ ] 人工审批流后端接口与持久化（`/ai/v1/agents/approve` 契约未定义；B 端"审批"现为前端本地模拟）
- [x] 接入真实"鹿邑/于庄"惠农政策与病虫害防治样例文档（3 份：补贴政策 / 小麦病虫害 / 于庄小麦指南）

**完成标准：** 问答可溯源、内容合规可过审；Demo 语音提问于庄小麦/药材种植问题可得到依据手册的答复。◐ 部分达成（文本问答可溯源、内容可质检；语音与关键词双路召回未做）

---

## 阶段 4 · M 端与 C 端接入、数据大屏

- [x] `h5/` C 端 H5：商品列表、秒杀倒计时、结算下单（真实调用 backend checkout）、成功弹窗、PWA 离线、AI 农技问答
  - [ ] C 端订单跟踪 / 我的订单
- [ ] `mobile/` Flutter：登录、扫码发货、离线缓存、语音提问农技（**未开工，仅 `mobile/README.md` 规划**）
- [x] `web/` 数据大屏：Dashboard/Knowledge/Agents/Orders 四页 + 响应式 + 无障碍 + E2E（管理端数据现为演示快照，待后端只读聚合接口就绪后替换为真实 Query）
- [ ] 微信授权（openid）接入 H5 私域下单
- [x] 后端统一下单 API（`POST /orders/checkout`，H5/App 共用，已对齐 `docs/api-spec.yaml`）
  - [x] 后端订单列表/详情聚合查询 API（`GET /orders` + `GET /orders/{orderNo}`，已对齐 docs/api-spec.yaml）

**完成标准：** 三端均可访问同一后端；移动端弱网可用；大屏数据实时刷新。◐ 部分达成（B/C 端已通，M 端未启动、大屏为快照）

---

## 阶段 5 · 公域渠道与答辩打磨

- [ ] 抖音/快手 OpenAPI 沙箱对接（直播商品与订单回流）
- [ ] B2B 大宗集采门户（报价、批量下单、合同/批次）
- [x] 属地化演示数据脚本（于庄 3 SKU / 50 订单 / Outbox / RAG 文档，`deploy/seed-realistic-data.sql` + `scripts/seed_rag.sh`）
  - [ ] 多租户/子账号演示数据脚本（村委/合作社/子账号）
- [x] Docker Compose 全栈一键拉起（backend/ai-service/web/h5 已编排入 `rural-network`）
  - [ ] mobile 落地后补入编排
- [ ] 演示视频脚本与 PPT 素材整理（含压测曲线、RAG 溯源截图）

**完成标准：** 全栈 `docker compose up` 一键演示；答辩材料齐全。◐ 部分达成（全栈编排已就绪，公域渠道/答辩素材未做）

---

## 里程碑与依赖

| 里程碑 | 依赖 | 状态 |
|--------|------|------|
| M0 骨架基线 | 阶段 0 | ✅ 已完成 |
| M1 业务闭环 | M0 | ◐ 基本完成（缺认证/用户体系、商品写接口、订单查询聚合接口） |
| M2 高并发 | M1 | ◐ 核心完成（缺压测脚本、渠道适配器骨架） |
| M3 AI 能力 | M0 + M1 数据 | ◐ 大部分完成（缺关键词双路召回/重排、PDF 解析、ASR、审批接口） |
| M4 全端联通 | M1 + M2 + M3 | ◐ 进行中（h5/web 已交付，M 端 Flutter 为最大缺口） |
| M5 公域演示 | M4 | ○ 未开始 |
