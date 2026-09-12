# 未完成项清单与后续实施建议（2026-09-12）

> 配套文档：`docs/remediation-report-2026-09-12.md`（已完成部分的详细记录）
> 基线：`main @ 5a4155b` → 本批整改后
> 结论：**阶段 A、B 全部完成；阶段 C/D/E/F/G 的核心已落地；支付与媒体按用户指令明确保留。**
> 本文只列「未完成」，逐项说明现状、影响、依赖与建议实现路径，避免把规划写成已实现。

## 一、按用户指令保留（不算缺陷，但尚未实现）

### 1. 支付（阶段 E）

| 项 | 现状 | 影响 | 建议路径 |
|---|---|---|---|
| `PaymentProvider` 抽象 | 未实现；仅有 demo 沙箱 `POST /orders/{orderNo}/pay/sandbox` | 生产无真实支付入口 | 定义 `PaymentProvider` 接口（createOrder/query/refund/verifyNotify），实现 `SandboxPaymentProvider`（仅 demo）与 `WechatPayProvider`/`AlipayProvider` 骨架 |
| 幂等支付单 | 未实现 | 重复回调可能重复入账 | 新增 `t_payment_order`（out_trade_no 唯一、状态机 INIT→PAID→REFUNDED），与订单状态门联动 |
| 回调验签 | 未实现 | 无法安全接收渠道回调 | 微信 v3 平台证书验签 / 支付宝 RSA2 验签，回调幂等 + 金额校验 |
| 退款 / 对账 | 未实现 | 售后无法闭环 | 退款申请单 + 状态机 + 日对账任务（差异告警） |
| 生产禁用策略 | 已具备「未配置即禁用」的语义（沙箱仅 demo），但缺少**显式开关与提示** | 生产若误调沙箱会写入 PROCESSING | 增加 `yuzhuang.payment.provider=NONE\|SANDBOX\|WECHAT\|ALIPAY`，`NONE` 时沙箱端点直接 503 + 明确文案 |

### 2. 媒体 / 对象存储（阶段 C）

| 项 | 现状 | 影响 | 建议路径 |
|---|---|---|---|
| 对象存储层 | 未实现（无 MinIO/S3 适配） | 商家无法上传真实商品图 | `MediaStorageService` 接口 + `MinioMediaStorage`（demo）/`S3MediaStorage`（生产，S3/COS/OSS 兼容） |
| 图片上传/校验 | 未实现 | — | 服务端校验 MIME + 扩展名 + **魔数** + 尺寸上限（如 ≤10MB、≤4096px），安全对象键（`tenantId/uuid.ext`，禁止用户原名） |
| 商品图片 UI | 未实现（`/b/merchant` 仅商品列表与上下架） | 商家不能维护图片 | 上传/预览/删除/排序/设主图，写审计 |
| `imageUrl` 真实化 | 仍为 `https://cdn.yuzhuang.example/specialty/{skuCode}.jpg` 占位 | H5 显示的是占位图 | 媒体层落地后改由 `t_product_media` 主图解析；无图时保留通用占位 |
| 孤儿清理 | 未实现 | 存储垃圾累积 | 定时任务比对 DB 引用与对象列表 |

## 二、未完成的工程项（按阶段）

### 阶段 D · 政府治理大屏

- **真实事件流**：大屏「事件流水」仍为客户端模拟；当前已**生产默认隐藏**并标注“非实时”
  （`NEXT_PUBLIC_SIMULATED_REALTIME_FEED=true` 才显示）。
  建议：后端基于 `t_outbox_event`/Redis Streams 提供**鉴权后**的 SSE 端点
  （`GET /api/v1/events/stream`，按授权范围过滤），前端在 production 才显示该卡片。
- **下钻与脱敏**：当前提供租户维度聚合下钻（不含 PII）；**订单级下钻**（含联系人）未实现，
  需要脱敏视图 + 每次下钻写审计。
- **导出**：已实现 CSV（含导出审计）；**Excel(xlsx)** 未实现（如需可用 POI，注意内存与流式写出）。

### 阶段 E · 消费者闭环

- **购物车 / 多 SKU**：H5 当前为单品下单（可多次下单）；购物车、多 SKU 合并结算未实现。
- **订单列表**：订单中心需逐单「订单号 + 凭证」查询；无「我的订单列表」。
  建议：本地凭证列表 + 批量查询端点（`POST /orders/guest/list`，body 传凭证数组，逐条校验后返回脱敏摘要）。
- **售后 / 退款申请**：未实现（依赖支付模块）。
- **微信 OAuth/openid**：未实现（后续可用 openid 绑定订单，替代纯凭证模式）。
- **物流轨迹**：仅记录承运商 + 单号，**未对接快递 100/菜鸟等轨迹查询**。

### 阶段 F · AI / RAG

- **需求资料导入流程**：`ai-service/data/raw_docs` 有 3 份样例文本，但**未建立“来源可核验的鹿邑/于庄政策与农技资料导入流程”**
  （当前样例文本的来源与版本未标注、未审核）。
  建议：每份资料登记来源 URL/发文号/版本/生效期，导入后状态为 `PENDING_REVIEW`，由村委/平台管理员审核后可用于问答。
- **RAG 评测集与门槛**：未建立。建议离线评测集（≥30 问）
  指标：召回率、引用存在率、答案依据一致性、无依据拒答率、跨租户隔离；在干净测试库可重复运行并设超时。
- **知识 PDF/原文件**：PDF 解析已支持（可选依赖 pypdf）；**原文件对象存储**未实现（依赖媒体层）。
- **匿名问答限流**：进程内滑动窗口（单实例）；多实例需换 Redis 限流。
- **AI 能力可视化**：已提供 `/capabilities` 与 `/readyz`；B 端**缺少运维配置页**（当前需调用接口查看）。

### 阶段 G · 移动端与渠道

- **`mobile/`（Flutter M 端）**：仅有 README，**未实现**（登录/任务/扫码发货/离线同步/语音问答）。
  已在 README/roadmap 中撤销“三端已完成”的表述，只列路线图。
- **渠道订单回流**：抖音/快手/B2B 仅有适配器骨架 + 签名校验 + disabled 配置；
  **实际回调落单、幂等、库存扣减、回流对账未实现**（需开放平台凭证与接口文档）。

### 阶段 H · 质量与验收

- **Docker Compose 全栈启动演练**：未执行（仅做了 `compose config` 语法校验）。
- **Playwright E2E**：`web/e2e/core-flows.spec.ts` 未扩展；登录/过期会话/角色跳转、商家商品+图片、H5 下单、政府只读、AI 权限、订单支付状态等关键路径未覆盖。
- **压力测试**：无 k6/JMeter/wrk 脚本；下单幂等、防超卖、限流与恢复未做可重复压测。
- **快速批量下单/库存并发**：已有 `OrderCheckoutConcurrencyTest`（H2），但未在真实 PostgreSQL 下复测。
- **安全越权端到端演练**：已有 23 例参数化矩阵测试（MockMvc）；未做容器环境下的真实 HTTP 越权演练。
- **前端单测覆盖**：web 2 例、h5 2 例（仅格式化函数），业务组件无单测。
- **文档校准残留**：`docs/audit-verification.md`、`docs/frontend-improvement-progress.md`、
  `docs/frontend-api-integration.md`、`backend/README.md`、`ai-service/README.md`、`h5/README.md`
  尚未随本批整改回填（其中若含“待办/未实现”描述可能已过时）。代码与文档冲突时以代码为准。

## 三、必须由外部提供的事项（代码侧已完成适配/校验，未开通不得宣称可用）

| # | 事项 | 当前代码行为 | 需要用户提供 |
|---|---|---|---|
| 1 | 域名 + TLS 证书 | `deploy/nginx/nginx.prod.conf.template` 参数化；缺证书无法验证 HTTPS | 域名、DNS 控制权、证书（或 Let's Encrypt 签发权限） |
| 2 | 微信/支付宝商户 | 生产无凭证 → 支付禁用（不伪造成功） | 商户号、AppID、API v3 密钥 / 应用私钥与支付宝公钥 |
| 3 | 短信通道 | 未启用验证码/通知 | 短信服务商 AK/SK、签名与模板报备 |
| 4 | 对象存储 | 未配置 → 图片上传不可用 | S3/COS/OSS Endpoint、Bucket、AK/SK（或 MinIO 自建） |
| 5 | DeepSeek / Embedding | 未配置 → `/readyz` 不通过、能力标记 `UNAVAILABLE` | `DEEPSEEK_API_KEY`、`EMBEDDING_API_KEY`（填 `ai-service/.env`） |
| 6 | 抖音 / 快手 / B2B | 默认 disabled → 回调 503/C5003 | 开放平台应用凭证、回调签名算法与接口文档 |
| 7 | 快递轨迹查询 | 仅存承运商 + 单号 | 快递 100/菜鸟等接口凭证 |

## 四、验收基线逐项状态（含未满足项）

| 验收项 | 状态 | 缺口 |
|---|---|---|
| 1. production fail-fast | ✅ | — |
| 2. 生产不建演示账号/数据 | ✅ | — |
| 3. 端点×角色×租户矩阵测试 | ✅（23 例） | 容器环境真实 HTTP 越权演练未做 |
| 4. 农户/政府/商家边界 | ✅ | 订单级 PII 下钻（政府）未实现，故无对应测试 |
| 5. `/b/*` 会话与跳转 | ✅ | E2E 未覆盖 |
| 6. 商家商品管理 + **图片** | ⚠️ 部分 | **媒体/对象存储未实现**（按指令保留） |
| 7. H5 展示真实商品与**真实媒体** | ⚠️ 部分 | 商品数据真实；媒体仍为占位地址 |
| 8. 下单店铺/SKU 作用域 + 支付状态机 | ⚠️ 部分 | 下单校验已实现；**支付状态机/回调/退款未实现**（按指令保留） |
| 9. AI 知识权限与不伪造 | ✅ | 原文件对象存储、评测集未做 |
| 10. 政府只读 + 口径 + 更新时间 | ✅ | 订单级下钻脱敏未做 |
| 11. 大屏事件真实来源，否则隐藏 | ⚠️ 部分 | 已生产隐藏并标注；**真实事件流未实现** |
| 12. 一键启动 + 全类测试 | ⚠️ 部分 | Compose 全栈启动演练、E2E、压测未执行 |
| 13. 仅暴露 80/443 + HTTPS/限流 | ✅ 配置就绪 | 需证书方可端到端验证 |
| 14. 文档与实测一致 | ⚠️ 部分 | 若干部 README/审计文档未回填 |

## 五、如何继续（按建议顺序）

1. **接支付**：先加 `yuzhuang.payment.provider` 开关与 `NONE` 禁用语义，再实现 `PaymentProvider` 与微信/支付宝适配器（含回调验签与幂等测试）。
2. **接媒体**：`MediaStorageService` + MinIO(demo)/S3(prod) + 上传统计与孤儿清理 + 商家图片 UI；
   随后把 `ProductQueryServiceImpl`/`ProductAdminServiceImpl` 的占位 `imageUrl` 换成 `t_product_media` 主图解析。
3. **政府真实事件流**：`GET /api/v1/events/stream`（鉴权 + 授权范围过滤）→ 前端替换模拟卡片。
4. **AI 资料与评测**：建立来源登记表 + 导入脚本 + 审核流；补 RAG 评测集与门槛（可重复、带超时）。
5. **H5 闭环补齐**：购物车/多 SKU、订单列表（批量凭证校验）、售后申请。
6. **阶段 H 收口**：Compose 全栈启动演练 → Playwright 关键 E2E → k6 压测 → 文档回填。

## 六、本批已验证命令（可复现）

```bash
# backend（157 tests）
cd backend && mvn test

# ai-service 离线（35 passed；全量需本地 PostgreSQL+pgvector）
cd ai-service && python -m pytest tests/ -q \
  --ignore=tests/test_qa_flow.py --ignore=tests/test_rag_hybrid.py

# web
cd web && npm run typecheck && npm run lint && npm test && npm run build

# h5
cd h5 && npm run typecheck && npm run lint && npm test && npm run build

# 契约与编排语法
python -c "import yaml;yaml.safe_load(open('docs/api-spec.yaml',encoding='utf-8'))"
docker compose -f deploy/docker-compose.yml config --quiet
docker compose --env-file .env.production.example -f deploy/docker-compose.prod.yml config --quiet
```

> 说明：`ai-service` 的 `test_qa_flow.py` / `test_rag_hybrid.py` 依赖本地 PostgreSQL+pgvector
> 实例，本机未执行，因此**不宣称 AI 全量测试通过**；`docker compose up` 全栈启动、
> Playwright E2E、k6 压测在本批中均**未执行**。

