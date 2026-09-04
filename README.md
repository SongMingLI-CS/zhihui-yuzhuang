# 智汇于庄：基于大模型与高并发引擎的特色产业数字助农中台

面向**全国大学生乡村振兴大赛**的助农平台项目。本项目聚焦**河南省周口市鹿邑县试量镇于庄**，将大模型 AI（RAG 与多智能体）与高并发微服务后端深度融合，构建"前端轻量化、中间移动协同、后台集中调度"的现代数字化助农平台，并以 **Roo Code + DeepSeek-R1 的全自动 AI 编程流水线**高效落地。

## 落地场景与痛点

赋能鹿邑县试量镇于庄，解决乡村数字化四大痛点：

- **农业生产信息不对称**：农技知识分散在纸质手册与经验中，农户难以获取；
- **乡村政策落地难**：惠农政策文件多、渠道散，农户看不懂、找不到；
- **农产品电商销售渠道单一**：缺少电商运营能力，无带货内容与定价策略支持；
- **大流量下系统易崩溃**：直播带货瞬时洪峰导致订单超卖、系统雪崩。

## 总体架构（三端鼎立 + 工业级底座）

```text
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  C 端 · 消费引流  │   │  M 端 · 移动协同  │   │  B 端 · 中枢大脑  │
│  极致轻量 H5     │   │  Flutter 工作台   │   │  Next.js 数据大屏 │
│ 微信/社群快速下单  │   │ 弱网离线/扫码发货  │   │ RAG库/多租户/策略 │
└──────┬───────┘   └──────┬───────┘   └──────┬───────┘
       │  下单/直播聚合    │ 语音提问/农技问答  │  知识库维护/审批
       └──────────────┬───┴──────────────────┘
                      ▼
        ┌─────────────────────────────────────┐
        │         Nginx 统一接入网关           │
        └───────────────┬─────────────────────┘
            ┌───────────┴───────────┐
            ▼                       ▼
 ┌────────────────────┐   ┌────────────────────┐
 │  Spring Boot 业务后端 │   │   FastAPI AI 服务   │
 │ 用户/商品/订单/库存     │   │  RAG + pgvector    │
 │ Redis Streams 削峰   │   │  DeepSeek 多智能体   │
 │ Outbox + CAS 防超卖   │   │  语音转写/检索问答    │
 └──────────┬─────────┘   └─────────┬──────────┘
            │                        │
            └────────────┬───────────┘
                         ▼
        ┌─────────────────────────────────────┐
        │  PostgreSQL(pgvector)   +   Redis    │
        │  业务数据/向量数据/租户隔离  缓存/会话/消息流 │
        └─────────────────────────────────────┘
```

### 三端分工

| 端 | 定位 | 载体 | 核心能力 |
|----|------|------|----------|
| **C 端消费引流层** | 面向消费者的下单入口 | 极致轻量 H5 | 在微信群/社群中快速下单、抢购，无需下载 App |
| **M 端移动协同层** | 农户/合作社移动工作台 | Flutter | 弱网离线缓存、扫码发货、语音唤醒 AI 农技问答 |
| **B 端中枢大脑** | 村委/运营管理后台 | Next.js PC 端 | RAG 知识库维护、多租户账号分配、大模型智能体策略审批、数据大屏 |

### 工业级底座

| 组件 | 作用 |
|------|------|
| **Docker Compose** | 一键编排全部基础设施与服务 |
| **Nginx** | 统一接入网关（API / AI / 各端路由） |
| **PostgreSQL + pgvector** | 关系型业务数据 + 向量数据 |
| **Redis Streams** | 消息队列与流量削峰填谷 |
| **多租户 Tenant ID** | 底层数据隔离 |

## 四大核心功能模块

### 1. 智能农技与政策检索内核（RAG 系统）

- 将鹿邑县惠农政策、病虫害防治手册等 PDF 文档**结构化切片 + 向量化**，存入 pgvector；
- 农户通过 Flutter App 语音提问时，采用"**向量相似度 + 关键词**"双路召回；
- 强制大模型**仅依据检索片段回答**，实现高准确率、可追溯的防幻觉农技问答。

### 2. 电商营销多智能体工作流（Multi-Agent Matrix）

- 接入 DeepSeek API，设置多个协同智能体（热点捕捉 Agent、小红书/抖音带货文案 Agent、合规质检 Agent 等）；
- 输入于庄特色农产品标签后，自动生成**合规且有网感**的带货文案与短视频脚本；
- 基于历史销售数据输出**动态定价策略建议**。

### 3. 高并发订单与物流调度引擎（分布式后端）

- 针对农产品直播带货流量洪峰，采用 **Redis Streams** 消息流异步削峰填谷；
- 引入 **Outbox 模式**（发件箱）与 **CAS 乐观锁**处理库存扣减，解决高并发"超卖"问题；
- 通过底层 **Tenant ID** 实现多租户数据隔离。

### 4. 全渠道聚合调度中台

- 不盲目开发独立 App，由后端统一接管：
  - **H5 私域**（微信/社群）订单流；
  - **抖音/快手 OpenAPI** 公域直播流；
  - **Next.js B2B 网页端**大宗集采门户订单。

## 技术栈

- **业务后端：** Spring Boot（Java）——用户/商品/订单/库存等高并发核心业务
- **AI 服务：** FastAPI（Python）——大模型调用、RAG 检索、多智能体编排
- **C 端：** 轻量 H5（移动端优先）
- **M 端：** Flutter（Android/iOS 移动工作台）
- **B 端：** Next.js（React）——管理后台与数据大屏
- **数据库：** PostgreSQL + pgvector
- **消息/缓存：** Redis（Streams / 会话 / 分布式锁）
- **网关/部署：** Nginx + Docker Compose

## 目录结构

```text
.
├── backend/                  # Spring Boot 高并发业务后端（用户/商品/订单/库存/多租户）
├── ai-service/               # FastAPI 大模型与 RAG 服务（DeepSeek 多智能体）
├── web/                      # B 端中枢大脑：Next.js PC 数据大屏/村委运营后台
├── h5/                       # C 端消费引流：极致轻量 H5（私域快速下单）
├── mobile/                   # M 端移动协同：Flutter 农户/合作社工作台
├── deploy/                   # 容器化及部署配置
│   ├── docker-compose.yml    # PostgreSQL(pgvector) / Redis / Nginx 网关
│   └── nginx/                # 网关路由配置
├── docs/                     # 架构与设计文档
│   ├── architecture.md       # 总体架构文档
│   └── roadmap.md            # 开发路线图
└── README.md
```

## 快速开始（启动基础设施）

### 前置条件

请先安装 Docker Desktop，或安装 Docker Engine 与 Docker Compose 插件。

### 启动基础设施（数据库 / 缓存 / 网关）

在项目根目录运行：

```bash
docker compose -f deploy/docker-compose.yml up -d
```

默认连接信息：

| 服务 | 地址 | 说明 |
|------|------|------|
| PostgreSQL | `localhost:5432` | 库 `rural_revitalization` / 用户 `rural_user` / 密码 `rural_password` |
| Redis | `localhost:6379` | 含 AOF 持久化 |
| Nginx 网关 | `localhost:80` | `/api` → 业务后端、`/ai` → AI 服务（待服务接入） |

可通过 shell 环境变量覆盖默认值（详见 [`deploy/docker-compose.yml`](deploy/docker-compose.yml)）：

```bash
POSTGRES_PASSWORD=your_secure_password \
POSTGRES_PORT=15432 \
REDIS_PORT=16379 \
GATEWAY_PORT=8080 \
docker compose -f deploy/docker-compose.yml up -d
```

首次启动 PostgreSQL 后，可启用向量扩展：

```bash
docker compose -f deploy/docker-compose.yml exec postgres \
  psql -U rural_user -d rural_revitalization \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### 查看状态 / 日志 / 停止

```bash
docker compose -f deploy/docker-compose.yml ps
docker compose -f deploy/docker-compose.yml logs -f
docker compose -f deploy/docker-compose.yml down
docker compose -f deploy/docker-compose.yml down -v   # 同时清除数据卷
```

## 开发路线图

各模块实现顺序与里程碑见 [`docs/roadmap.md`](docs/roadmap.md)，总体架构设计见 [`docs/architecture.md`](docs/architecture.md)。
