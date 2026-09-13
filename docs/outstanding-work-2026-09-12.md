# 未完成项清单（第三版 · 2026-09-12）

> 本文是**当前唯一的未完成项清单**，已替换此前两个版本（旧版中已完成项已移除）。
> 配套：`docs/remediation-report-2026-09-12.md`（已完成部分的详细记录）
> 基线：`main @ 5a4155b` → 四批整改完成
> 当前实测：backend **162 tests 全绿**；ai-service 离线 **42 passed**；web/h5 的 typecheck+lint+test+build 全通过；
> `docs/api-spec.yaml` 合法（**41 条 path**）；两份 Compose 语法校验通过。

---

## 一、按用户指令保留（明确不做，非缺陷）

### 1.1 支付（阶段 E）

| 项 | 现状 | 影响 | 建议路径 |
|---|---|---|---|
| `PaymentProvider` 抽象 | 未实现；仅 demo 沙箱 `POST /orders/{orderNo}/pay/sandbox` | 生产无真实支付入口 | 定义 `createOrder/query/refund/verifyNotify`，实现 Sandbox（仅 demo）与微信/支付宝适配器骨架 |
| 幂等支付单 | 未实现 | 重复回调可能重复入账 | 新增 `t_payment_order`（out_trade_no 唯一 + 状态机 INIT→PAID→REFUNDED），与订单状态门联动 |
| 回调验签 | 未实现 | 无法安全接收渠道回调 | 微信 v3 平台证书验签 / 支付宝 RSA2 验签 + 金额校验 + 幂等 |
| 退款 / 对账 | 未实现 | 售后与账务无法闭环 | 退款申请单 + 状态机 + 日对账任务（差异告警） |
| 生产禁用策略 | 语义上「未配置即不可用」，但缺显式开关与提示 | 误调沙箱会写入 PROCESSING | 增加 `yuzhuang.payment.provider=NONE|SANDBOX|WECHAT|ALIPAY`，`NONE` 时沙箱端点直接 503 + 明确文案 |

### 1.2 媒体 / 对象存储（阶段 C）

| 项 | 现状 | 影响 | 建议路径 |
|---|---|---|---|
| 对象存储层 | 未实现（无 MinIO/S3 适配） | 商家无法上传真实商品图 | `MediaStorageService` + `MinioMediaStorage`(demo)/`S3MediaStorage`(生产，S3/COS/OSS 兼容) |
| 图片上传与校验 | 未实现 | — | 服务端校验 MIME + 扩展名 + **魔数** + 尺寸/大小上限；安全对象键（`tenantId/uuid.ext`，禁用用户原名） |
| 商品图片 UI | 未实现（`/b/merchant` 仅列表与上下架） | 商家不能维护图片 | 上传/预览/删除/排序/设主图，写审计 |
| `imageUrl` 真实化 | 仍为 `https://cdn.yuzhuang.example/specialty/{skuCode}.jpg` 占位 | H5 展示占位图 | 媒体层落地后改用 `t_product_media` 主图解析；无图保留通用占位 |
| 孤儿清理 | 未实现 | 存储垃圾累积 | 定时比对 DB 引用与对象列表并清理 |

---

## 二、当前未完成的工程项（按阶段）

### 阶段 D · 政府治理大屏

- **订单级下钻 + 脱敏**：现仅提供**租户维度**聚合下钻（无 PII）；**订单级下钻**（含联系人/地址）未实现，
  需脱敏视图 + 每次下钻写审计（`GOV_DRILLDOWN`）。
- **Excel(xlsx) 导出**：CSV 已实现（含 `GOV_EXPORT` 审计）；xlsx 未实现（如需可用 POI，注意流式写出与内存）。

### 阶段 E · 消费者闭环

- **购物车 / 多 SKU**：H5 为单品下单（可多次下单）；购物车与多 SKU 合并结算未实现。
- **售后 / 退款申请**：未实现（依赖支付模块）。
- **微信 OAuth / openid 绑定**：未实现（当前依赖「订单号 + 一次性查询凭证」）。
- **物流轨迹查询**：仅记录承运商 + 单号，**未对接**快递 100/菜鸟等轨迹接口。
- **订单列表规模**：批量查询单次上限 20 笔（本机记录最多保留 20 条），超量场景未设计。

### 阶段 F · AI / RAG

- **官方资料核验与导入流程**：`data/raw_docs/sources.json` 已建立（未核验资料强制标注 `note`），
  **但官方来源仍未核验**（缺发文单位/发文号/链接/生效期）——核验前只能用于检索链路演示，不得作为政策依据。
- **知识原文件对象存储**：未实现（依赖媒体层）；当前仅保存切片与内容哈希/版本元数据。
- **多实例匿名问答限流**：进程内滑动窗口（单实例）；多实例需换 Redis 计数。
- **AI 运维配置页**：`/ai/v1/capabilities` 与 `/readyz` 已提供，但 B 端**无可视化配置状态页**。
- **评测集规模**：当前 6 条用例（含 2 条应拒答），仅覆盖样例资料；资料核验后需扩充并复核阈值。

### 阶段 G · 移动端与渠道

- **`mobile/` Flutter M 端**：仅有 README，**未实现**（登录/任务/扫码发货/离线同步/语音问答）；
  README/roadmap 已撤销“三端已完成”表述，仅列路线图。
- **渠道订单回流**：抖音/快手/B2B 仅有适配器骨架 + HMAC 签名校验 + disabled 配置；
  **回调落单、幂等、库存扣减、回流对账未实现**（需开放平台凭证与接口文档）。

### 阶段 H · 质量与验收

- **容器全栈启动演练**：未执行（仅 `compose config` 语法校验）。
- **Playwright E2E 执行**：`web/e2e/core-flows.spec.ts` 与 `web/e2e/roles-and-session.spec.ts` 已就绪，**未运行**。
- **k6 压测执行**：`scripts/loadtest/checkout.js` 已就绪（幂等/防超卖/限流三场景），**未运行**，文档无吞吐量数字。
- **真实 HTTP 越权演练**：已有 23 例 MockMvc 参数化矩阵；未在容器环境做端到端越权演练。
- **PostgreSQL 并发复测**：`OrderCheckoutConcurrencyTest` 跑在 H2；未在真实 PostgreSQL 复测 CAS 防超卖。
- **前端业务单测**：web 2 例、h5 2 例（仅格式化函数）；业务组件（商家商品、政府大屏、订单中心）无单测。
- **文档回填残留**：`docs/audit-verification.md`、`docs/frontend-improvement-progress.md`、
  `docs/frontend-api-integration.md`、`backend/README.md`、`ai-service/README.md`、`h5/README.md`
  未随四批整改回填（代码与文档冲突时以代码为准）。
- **AI 全量测试**：`test_qa_flow.py` / `test_rag_hybrid.py` 依赖本地 PostgreSQL+pgvector，
  本机未执行 → **不宣称 AI 全量测试通过**。

---

## 三、必须由外部提供的事项（代码侧已完成适配/校验，未开通不得宣称可用）

| # | 事项 | 当前代码行为 | 需要用户提供 |
|---|---|---|---|
| 1 | 域名 + TLS 证书 | `deploy/nginx/nginx.prod.conf.template` 已参数化；缺证书无法验证 HTTPS | 域名、DNS 控制权、证书（或 Let's Encrypt 签发权限） |
| 2 | 微信/支付宝商户 | 未配置 → 支付不可用（不伪造成功） | 商户号、AppID、API v3 密钥 / 应用私钥与支付宝公钥 |
| 3 | 短信通道 | 未启用验证码/通知 | 短信服务商 AK/SK、签名与模板报备 |
| 4 | 对象存储 | 未配置 → 图片上传不可用 | S3/COS/OSS Endpoint、Bucket、AK/SK（或自建 MinIO） |
| 5 | DeepSeek / Embedding | 未配置 → `/readyz` 不通过、能力标记 `UNAVAILABLE` | `DEEPSEEK_API_KEY`、`EMBEDDING_API_KEY`（填 `ai-service/.env`） |
| 6 | 抖音 / 快手 / B2B | 默认 disabled → 回调 503/C5003 | 开放平台应用凭证、回调签名算法与接口文档 |
| 7 | 快递轨迹查询 | 仅存承运商 + 单号 | 快递 100/菜鸟等接口凭证 |
| 8 | 官方政策/农技资料 | 未核验资料仅用于链路演示 | 官方文件（含发文单位/发文号/链接/生效期） |

---

## 四、验收基线逐项状态（14 项）

| # | 验收项 | 状态 | 未满足的具体缺口 |
|---|---|---|---|
| 1 | production 缺密钥/域名/存储/支付配置 fail-fast | ✅ | 域名/存储/支付随外部开通 |
| 2 | 生产不创建演示账号/演示数据 | ✅ | — |
| 3 | 端点×角色×租户自动化矩阵，越权后端拒绝 | ✅（23 例） | 容器环境真实 HTTP 越权演练未做 |
| 4 | 农户不可读大盘/全租户订单；政府不可写；商家不可跨租户 | ✅ | 政府订单级下钻（含 PII）未实现，故无对应测试 |
| 5 | `/b/*` 未登录直达 `/b/login`，过期会话不跳错 | ✅ | E2E 已写但未执行 |
| 6 | 商家可管理草稿/下架商品并上下架；**图片上传** | ⚠️ 部分 | **媒体/对象存储未实现（保留）** |
| 7 | H5 只展示真实发布商品与**真实媒体** | ⚠️ 部分 | 商品数据真实；媒体仍为占位地址（保留） |
| 8 | 下单严格校验店铺/SKU 作用域；支付状态机与幂等测试 | ⚠️ 部分 | 下单校验已实现；**支付 Provider/回调/退款未实现（保留）** |
| 9 | AI 知识上传/删除受 JWT 与角色约束；生产无密钥不伪造 | ✅ | 知识原文件对象存储、官方资料核验未完成 |
| 10 | 政府大屏只读、按授权聚合、显示口径与更新时间 | ✅ | 订单级脱敏下钻、xlsx 导出未实现 |
| 11 | 大屏事件真实来源，否则生产隐藏 | ✅ | 数据源为 Outbox 真实事件（轮询 + SSE）；模拟卡片仅显式开关下作演练 |
| 12 | 干净环境一键启动、迁移可重复、全类测试通过 | ⚠️ 部分 | Compose 全栈启动、Playwright、k6 **未执行**；AI 全量测试未跑 |
| 13 | 仅暴露 80/443、HTTPS/安全头/限流、密钥不进仓库 | ✅ 配置就绪 | 需证书方可端到端验证 |
| 14 | OpenAPI/README/roadmap/部署文档与实测一致 | ⚠️ 部分 | 若干部 README 与审计文档未回填 |

---

## 五、建议实施顺序

1. **接支付**：先加 `yuzhuang.payment.provider` 开关与 `NONE` 语义（生产默认禁用），再实现 `PaymentProvider` 与微信/支付宝适配器（含回调验签、幂等、退款、对账测试）。
2. **接媒体**：`MediaStorageService` + MinIO(demo)/S3(prod) → 上传统计与孤儿清理 → 商家图片 UI → 用真实主图替换占位 `imageUrl`。
3. **政府深化**：订单级脱敏下钻（写审计）+ xlsx 导出。
4. **H5 闭环**：购物车/多 SKU、售后申请、微信 openid 绑定、物流轨迹查询。
5. **AI 收口**：官方资料核验后导入（补 publisher/sourceUrl/issuedDate）→ 扩充评测集并复核阈值 → 原文件对象存储 → AI 配置状态页。
6. **质量收口**：Compose 全栈启动演练 → Playwright 执行 → k6 执行（如实记录实测指标）→ 前端业务单测 → 文档回填。

---

## 六、最近一次验证命令（可复现）

```bash
# backend：162 tests, 0 failures, 0 errors
cd backend && mvn test

# ai-service 离线：42 passed（全量 11 个中 3 个 QA/DB 用例需本地 PostgreSQL+pgvector，未跑）
cd ai-service && python -m pytest tests/ -q \
  --ignore=tests/test_qa_flow.py --ignore=tests/test_rag_hybrid.py

# RAG 评测（需已入库知识 + pgvector）
cd ai-service && python scripts/eval_rag.py --k 5

# web / h5
cd web && npm run typecheck && npm run lint && npm test && npm run build
cd h5  && npm run typecheck && npm run lint && npm test && npm run build

# 契约与编排语法
python -c "import yaml;yaml.safe_load(open('docs/api-spec.yaml',encoding='utf-8'))"
docker compose -f deploy/docker-compose.yml config --quiet
docker compose --env-file .env.production.example -f deploy/docker-compose.prod.yml config --quiet

# 未执行（需先起全栈）：E2E 与压测
# cd web && npx playwright test
# k6 run -e BASE_URL=http://localhost -e SKU_ID=1001 -e STOCK=50 scripts/loadtest/checkout.js
```

> 以上执行状态即为事实：**未执行**的项目在上表中已逐项标注，不做“已通过”的推断。

