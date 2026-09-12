# web · B 端中枢大脑（Next.js PC 端）

面向村委/合作社运营的 B 端管理中台与数据大屏，提供数据看板、知识库问答检索、
多智能体营销文案生成与广告法合规审批、多渠道订单与出库履约管理等能力。

- 框架：Next.js 14（App Router）+ React 18 + TypeScript 5
- 样式：Tailwind CSS 3 + 品牌绿/金主题（全局动画、`.panel`/`.num` 等工具类）
- 图表：ECharts（echarts-for-react）面积趋势图、销售占比环形图
- 图标：lucide-react
- 数据请求：axios（`ApiResponse` 信封解包 + `X-Tenant-Id` 多租户头）
- 网关路由：`/b/*` → `web:3000`（`basePath:'/b'`，浏览器直连同源网关 `/api/v1`、`/ai/v1`）
- 会话：`HttpOnly+Secure+SameSite` Cookie（`yz_session`）+ CSRF 双提交（`yz_csrf` / `X-CSRF-Token`）；令牌仅存内存，`SessionGuard` 负责 `/b/*` 路由级会话守卫

## 角色分区（2026-09-12 整改）

| 角色 | 落地页 | 可见导航 |
|---|---|---|
| `COOPERATIVE` | `/merchant` | 商品管理 · 订单出库 · 知识库 · 营销 Agent |
| `VILLAGE` | `/dashboard` | 治理大盘 · 商品管理 · 订单出库 · 知识库 · 营销 Agent |
| `GOVERNMENT` | `/gov` | 仅知识库（只读聚合大屏，无任何写操作入口） |
| `PLATFORM_ADMIN` | `/platform` | 治理大盘 · 租户/账号管理 |

> 导航过滤仅为体验；**授权一律由后端端点策略强制**（未登录 → `/b/login`，越权 → 403/A1003）。

| 新增路由 | 说明 | 消费接口 |
|---|---|---|
| `/merchant` | 商家商品工作台（含草稿/下架/归档，可上下架） | `GET /api/v1/merchant/products`、`PATCH /api/v1/products/{id}/status` |
| `/gov` | 政府只读治理大屏（口径/快照/范围元数据，无写按钮） | `GET /api/v1/gov/summary` |
| `/platform` | 平台管理（租户/账号/启停/重置密码） | `/api/v1/admin/**` |
| `/change-password` | 首次登录强制改密 | `POST /api/v1/auth/password/change` |

## 已交付模块

| 路由 | 模块 | 说明 | 数据 |
|------|------|------|------|
| `/b/dashboard` | 数据大屏 | 指标卡 + 7 日趋势面积图 + 销售渠道环形图 + 实时流水 | 演示快照 + 静态流 |
| `/b/knowledge` | 知识库 | 切片文档表 + 农技问答抽屉（Top-K 溯源引用） | 演示文档表 + **真实调用** `POST /ai/v1/qa/ask` |
| `/b/agents` | Agent 审批 | 营销文案生成器：TrendAgent→CopywriterAgent→ComplianceAgent 链路 + 三渠道文案 + 广告法质检 + 人工审批 | **真实调用** `POST /ai/v1/marketing/generate` |
| `/b/orders` | 订单履约 | 状态机看板 + 订单明细表 + 待出库队列（本地状态演示 READY→SHIPPED 出库） | 演示快照 |

### Dashboard
- 顶部指标卡：今日订单、GMV、待履约、缺货 SKU。
- 7 日销售趋势面积图、各渠道销售占比环形图（ECharts，主题色随值渐变）。
- 实时流水卡片（Redis Streams 语义静态模拟：下单/支付/出库事件流）。

### Knowledge（知识库 + QA 检索）
- 切片文档总览表（文档名/切片数/字符/状态/更新时间）。
- 「智能问答检索」抽屉：输入问题 → `POST /ai/v1/qa/ask`，展示回答、
  Top-K 引用切片（来源文档 + 相似度），疑似偏离时以脱敏提示兜底。

### Agents（营销多智能体 + 合规审批）
- 表单：产品名称、卖点（逐行）、目标人群、渠道偏好（朋友圈/小红书/直播口播）。
- 点击「AI 生成营销方案」→ 真实调用营销编排接口，逐步揭开三段 Agent 链路。
- 三渠道文案分页预览（可复制）；广告法合规报告（评分仪表、绝对化/疾病宣称风险词、
  改写建议、输入源命中定位）；「审批通过并同步至私域」或「驳回并重新生成」。

### Orders（订单履约看板）
- 4 张履约指标卡；6 阶段履约出库流水看板（待支付→已出库）。
- 多渠道订单明细表（H5 私域/抖音/快手/B2B 来源徽标、状态筛选、关键字搜索）。
- 「待出库队列」卡片支持一键出库（READY→SHIPPED）。

## 目录结构

```text
src/
├─ app/                     # App Router 页面
│  ├─ page.tsx              # / 重定向 → /b/dashboard
│  ├─ layout.tsx            # 根布局（AppShell：TopBar + Sidebar）
│  ├─ globals.css           # Tailwind + 主题变量 + 动画
│  ├─ dashboard/page.tsx    # 数据大屏
│  ├─ knowledge/page.tsx    # 知识库 + QA
│  ├─ agents/page.tsx       # 营销生成 / 审批
│  └─ orders/page.tsx       # 订单 / 出库
├─ components/
│  ├─ AppShell.tsx TopBar.tsx Sidebar.tsx navItems.ts
│  ├─ ui/                   # Badge / Card / StateView
│  ├─ dashboard/            # MetricCard / TrendChart / SalesShareChart / RealtimeFeed
│  ├─ knowledge/            # QaDrawer
│  └─ agents/               # AgentChain / CompliancePanel / CopyPreview / HighlightTerms
└─ lib/                     # config / tenant / types / http / format / demo / compliance / cn
```

## 契约对齐

- 接口 DTO、字段命名与业务状态码严格对齐 `docs/api-spec.yaml` 与
  `ai-service/app/schemas/*`（`MarketingGenerateRequest`、`ComplianceReport`、
  `AgriQARequest` 等）。
- 合规语义以服务端 `ComplianceAgent` 为准（确定性规则引擎）；前端 `lib/compliance.ts`
  仅镜像词库用于命中分组与输入源高亮，返回文案已是净化副本。

## 本地开发

```bash
cd web
npm install
npm run dev        # 默认 http://localhost:3000/b/
```

需要真实后端/AI 时先启动 `make infra-up`（或 `make all-up`）后，浏览器仍走网关相对路径；
仅开发时可用 `NEXT_PUBLIC_API_BASE` / `NEXT_PUBLIC_AI_BASE` 覆盖到网关地址。

## 容器部署

网关路径契约：`location /b/` → `web:3000`（见 `deploy/nginx/nginx.conf`）。
镜像采用 Next standalone 产物（见 `web/Dockerfile`）。

```bash
# 一键构建并启动 web + 网关（http://localhost/b/）
make web-up

# 常用操作
make web-build   # 本地类型检查构建 + 打包镜像
make web-down    # 停止 web 服务
make web-logs    # 查看 web 容器日志
```

## 端口约定

- 本地开发：`3000`
- 容器内：`3000`（加入 `rural-network`，经网关 `/b/*` 对外）
