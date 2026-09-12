# 智汇于庄 · 整改报告（2026-09-12）

> 基线：`main @ 5a4155b`（工作前 `git status` 仅有未跟踪的审计报告文件）
> 依据：`docs/project-audit-and-full-repair-prompt-2026-09-12.md`
> 范围：阶段 A、B 全量落地；阶段 C/D 核心（商家商品分页、政府只读聚合、平台管理）；阶段 E/F/G/H 部分落地并在第 6 节如实列明未完成项。

## 1. 改动摘要

### 阶段 A · 生产安全与配置

| 事项 | 实现 | 关键文件 |
|---|---|---|
| 环境/演示边界 | `AppEnvironment`（`APP_ENV` + `DEMO_MODE`）；`ProductionSafetyValidator` 启动 fail-fast | `config/AppEnvironment.java`、`config/ProductionSafetyValidator.java`、`config/SecurityDefaults.java` |
| 演示账号仅在显式 demo | `AuthDataInitializer` 加 `@ConditionalOnProperty(yuzhuang.demo.enabled=true)`；`application-demo.yml`/`application-dev.yml`/`application-test.yml` 显式开启 | `auth/config/AuthDataInitializer.java`、`application*.yml` |
| 生产缺密钥即失败 | `JwtService` 生产校验（拒绝开发默认值 / 长度 < 32）；`ProductionSafetyValidator` 同时拒绝 `DEMO_MODE=true`、demo/dev/test profile、`sql.init.mode=always`、开发默认库口令 | `auth/security/JwtService.java` |
| 账号生命周期 | 新增字段 `must_change_password/password_updated_at/last_login_at/disabled_at/disabled_reason/failed_login_count/locked_until/created_by/updated_at`；首登强制改密；连续 5 次失败锁定 15 分钟 | `V4__add_user_security_columns.sql`、`auth/entity/User.java`、`auth/service/impl/AuthServiceImpl.java` |
| 密码策略 | `PasswordPolicy`（≥8 位、含字母与数字、非用户名、非弱口令） | `auth/security/PasswordPolicy.java` |
| 会话改 Cookie | 登录下发 `HttpOnly+Secure+SameSite` Cookie `yz_session` 与可读 CSRF Cookie `yz_csrf`；过滤链支持 Cookie 认证；写接口 CSRF 双提交校验 | `auth/security/SessionCookieService.java`、`auth/filter/JwtAuthenticationFilter.java`、`auth/filter/CsrfProtectionFilter.java`、`auth/filter/CookieNames.java` |
| 移除公网演示口令提示 | 登录页删除固定账号口令提示，改为“账号由平台管理员发放、首登改密” | `web/src/app/login/page.tsx` |
| B 端会话/路由修复 | axios `withCredentials` + CSRF 头；401 按 `BASE_PATH` 跳 `/b/login`；新增路由级 `SessionGuard`；`/b` 分区与角色落地跳转 | `web/src/lib/http.ts`、`web/src/lib/config.ts`、`web/src/components/SessionGuard.tsx`、`web/src/app/change-password/page.tsx` |
| Nginx 生产模板 | TLS1.2+、HSTS/CSP/X-Frame-Options/nosniff、`server_tokens off`、登录/下单/AI/上传限流、分端点 body 限制、Swagger 与 AI docs 仅内网 | `deploy/nginx/nginx.prod.conf.template`、`deploy/nginx/proxy-common.inc` |
| Compose 生产编排 | 数据库/Redis 不映射宿主端口，仅网关 80/443；`APP_ENV=production`、`DEMO_MODE=false`；密钥必填 | `deploy/docker-compose.prod.yml`、`.env.production.example` |
| 备份/恢复 | `pg_dump -Fc` + gzip + 保留 14 份；恢复前停业务、重建库、`pg_restore` | `scripts/backup_db.sh`、`scripts/restore_db.sh` |
| 部署文档 | 边界表、部署步骤、安全要点、备份/恢复、迁移与回滚、外部待开通清单 | `deploy/PRODUCTION.md` |

### 阶段 B · 身份、RBAC 与多租户

| 事项 | 实现 | 关键文件 |
|---|---|---|
| 角色扩展 | 新增 `PLATFORM_ADMIN`、`GOVERNMENT`、`CONSUMER`；保留 `VILLAGE/COOPERATIVE/FARMER` | `auth/enums/UserRole.java` |
| 显式端点权限 | `EndpointSecurityPolicy`（PUBLIC / AUTHENTICATED / ROLES 三级、有序规则、未声明默认需认证）；`AuthGuardInterceptor` 改为策略驱动 | `web/security/EndpointSecurityPolicy.java`、`web/security/AuthGuardInterceptor.java` |
| deny-by-default 兜底 | `ApiFallbackController` 让所有 `/api/v1/**` 解析到 Handler，使未声明端点也受认证门禁 | `web/ApiFallbackController.java` |
| 政府授权范围 | `t_gov_scope` + `GovScope`/`GovScopeMapper` + `GovScopeService`（ALL/REGION/TENANT）；服务端据此推导只读范围 | `gov/**`、`V5__add_gov_scope_and_audit.sql` |
| 平台管理 API | 租户/账号增查、启停、重置密码（一次性临时口令）、授权范围授予/查询；返回体不含密码哈希；全程写审计 | `admin/**`、`audit/**` |
| 审计日志 | `t_audit_log` + `AuditService.record(...)`（自动补齐操作人/角色/租户/链路 ID） | `audit/**` |
| tenantId 不信任请求头 | 受保护端点一律取 `AuthContext.require().getTenantId()` 或授权范围解析结果 | 各 Controller/Service |
| AI 侧鉴权 | `app/auth.py`（同密钥 HS256 校验 + 角色白名单）接入知识上传/删除/列表与营销生成；匿名 QA 仅公共域并按 IP 限流 20r/m | `ai-service/app/auth.py`、`app/ratelimit.py`、`app/api/*.py` |
| 安全矩阵测试 | 20 组 端点×角色×租户 参数化断言 + 过期/篡改令牌 + ID 枚举 | `backend/.../web/SecurityMatrixTest.java` |

### 阶段 C/D · 商家工作台与政府大屏（核心）

- `GET /api/v1/merchant/products`：本租户全状态商品分页（含草稿/下架/归档），修复“下架即消失”；`ProductAdminService` 状态枚举扩展 `DRAFT/ARCHIVED`。
- `GET /api/v1/gov/summary` + `/gov/scope`：按授权范围聚合，返回 `snapshotAt`、范围元数据、口径定义（交易额/订单量/取消单）与分租户下钻，不含 PII；每次访问写审计。
- Web 新增 `/b/merchant`（商品列表+上下架）、`/b/gov`（只读大屏，无任何写按钮）、`/b/platform`（租户/账号/启停/重置密码）；导航按角色过滤（政府端不出现商品/营销/履约入口）。
- 「事件流水」卡片由客户端模拟改为**默认隐藏**（`NEXT_PUBLIC_SIMULATED_REALTIME_FEED=true` 才展示），并明确标注“演示：非生产实时”，不再被当作实时经营数据。

## 2. 数据库迁移清单

| 版本 | 文件 | 内容 | 兼容性 |
|---|---|---|---|
| V1 | `V1__init.sql` | 既有基线（商品/订单/明细/Outbox/租户/用户） | 未改动 |
| V2 | `V2__add_order_payment_closure_columns.sql` | 订单支付/关单列 | 未改动 |
| V3 | `V3__add_outbox_claim_lease_columns.sql` | Outbox 发布租约列 | 未改动 |
| V4 | `V4__add_user_security_columns.sql`（新增） | 账号安全与生命周期 9 列 | `ADD COLUMN IF NOT EXISTS`，存量库可直接升级 |
| V5 | `V5__add_gov_scope_and_audit.sql`（新增） | `t_gov_scope`、`t_audit_log`、`t_tenant.tenant_type/parent_region` | `CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`，向后兼容 |

H2 测试 schema（`backend/src/test/resources/schema-test.sql`）已同步新增列/表，保证测试与生产 schema 语义一致。

## 3. 角色权限矩阵（服务端强制）

| 端点 | PLATFORM_ADMIN | GOVERNMENT | VILLAGE | COOPERATIVE | FARMER | 匿名 |
|---|---|---|---|---|---|---|
| `GET /api/v1/healthz`、`/products*`、`POST /orders/checkout` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `POST /auth/login`、`/auth/logout`、`GET /auth/csrf` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /auth/me`、`POST /auth/password/change` | ✅ | ✅ | ✅ | ✅ | ✅ | 401 |
| `GET /dashboard/summary` | ✅ | ✅ | ✅ | ✅ | ❌ 403 | 401 |
| `GET /merchant/products`、商品写 | ✅ | ❌ 403 | ✅ | ✅ | ❌ 403 | 401 |
| `GET/POST /orders*`、履约写、沙箱支付 | ✅ | ❌ 403 | ✅ | ✅ | ❌ 403 | 401 |
| `GET /gov/**` | ✅（全域） | ✅（授权范围） | ❌ 403 | ❌ 403 | ❌ 403 | 401 |
| `/admin/**` | ✅ | ❌ 403 | ❌ 403 | ❌ 403 | ❌ 403 | 401 |
| AI 知识写 / 营销生成 | ✅ | ❌ 403 | ✅ | ✅ | ❌ 403 | 401 |
| AI 知识读 | ✅ | ✅ | ✅ | ✅ | ❌ 403 | 401 |
| AI 匿名问答 | — | — | — | — | — | ✅（仅 global 域 + 20r/m） |
| 未声明的 `/api/v1/**` | 已认证：404 | 同左 | 同左 | 同左 | 同左 | 401 |

## 4. 测试结果（精确数量，本地实跑）

| 套件 | 命令 | 结果 |
|---|---|---|
| backend | `cd backend && mvn test` | **157 tests, 0 failures, 0 errors**（基线 99 → 新增 58；详见第 10.5 节） |
| ai-service（离线） | `cd ai-service && python -m pytest tests/ -q --ignore=tests/test_qa_flow.py --ignore=tests/test_rag_hybrid.py` | **35 passed**（鉴权 6、AI 能力/降级边界 11、文档加载校验 11、营销审批状态机 5、营销链路 2 等） |
| ai-service（全量，需 PostgreSQL/pgvector） | `python -m pytest` | 未在本机全量执行：`test_qa_flow.py` / `test_rag_hybrid.py` 依赖本地 pgvector 实例（基线审计记录为“长时间不结束”）。已补齐鉴权与用例，但**不宣称全量通过** |
| web | `npm run typecheck` / `npm run lint` / `npm test` / `npm run build` | typecheck ✅、lint ✅（0 warnings/errors）、vitest **2 passed**、build ✅（14 路由，含新增 `/change-password`、`/gov`、`/merchant`、`/platform`） |
| h5 | `npm run build` | ✅（Vite 生产构建 + PWA 产物，16 precache entries） |
| api-spec | `python -c "yaml.safe_load(...)"` | ✅ 合法 YAML，22 条 path（新增会话/改密/商家/政府/平台管理契约） |
| Compose 语法 | `docker compose -f deploy/docker-compose.yml config --quiet`；`docker compose --env-file .env.production.example -f deploy/docker-compose.prod.yml config --quiet` | 两份编排均 **exit 0**（演示与生产编排语法/变量校验通过） |
| 全栈运行 | `docker compose ... up -d --build` + 网关/前端/E2E | **未执行**（本轮未做容器全栈启动与 Playwright；如实声明） |

> 说明：`WebContractTest` 原有一条断言为“农户可读本租户订单”，与验收基线 4（农户不得读取全租户订单与客户 PII）冲突。已将其改为“农户 403”，并新增 `villageCanReadTenantOrders` 保持覆盖——这是**按验收基线修正了编码错误行为的测试**，不是功能回退。

## 5. 生产部署与回滚

见 `deploy/PRODUCTION.md`。要点：

- 部署：`docker compose --env-file .env.production -f deploy/docker-compose.prod.yml up -d --build`
- 回滚：镜像回退到上一 tag + `scripts/restore_db.sh` 恢复迁移前备份；V4/V5 均为向后兼容新增，旧应用可与新 schema 共存。

## 6. 未完成 / 仍需外部条件的事项（如实声明）

**仍需用户提供的外部凭证或域名（代码侧仅完成适配/校验，未开通不得宣称可用）：**

1. 域名与 TLS 证书（DNS 控制权）——模板已参数化，未提供证书前无法完成 HTTPS 端到端验证。
2. 微信/支付宝商户号与 API v3 密钥——生产未配置时**支付禁用**；沙箱仅 demo 可用；退款/对账未实现。
3. 短信通道、对象存储云账号（S3/COS/OSS）——未配置时图片上传与短信验证不可用。
4. DeepSeek / Embedding 密钥——未配置时 AI 能力应标记不可用（readiness 细化仍待补全）。
5. 抖音/快手开放平台凭证——`ChannelAdapter` 未实现，渠道仍为未接入状态。

**本轮未完成的工程项（明确保留为后续阶段，不计入“已完成”）：**

- 阶段 C：商品域 Flyway 演进（描述/分类/单位/产地/媒体/规格多 SKU）、S3 兼容媒体层与图片上传 UI、资质文件、商家资料与库存预警、发货物流单号。
- 阶段 D：CSV/Excel 导出与导出审计；真实 SSE/WebSocket 事件流（当前模拟卡片已在生产默认隐藏并标注为非实时，接入真实事件流仍待办）。
- 阶段 E：购物车/多 SKU、`PaymentProvider` 抽象与幂等支付单/回调/退款/对账、订单中心与安全查询凭证、隐私政策页、H5 演示地址按钮随环境开关。
- 阶段 F：liveness/readiness 分离、知识文件类型扩展（PDF/原文件对象存储）、来源可核验资料导入、RAG 评测集门槛、营销生成任务持久化与真实审批 API/UI（当前审批仍为前端状态）。
- 阶段 G：移动端（`mobile/` 仍仅 README）与渠道适配器——已从“已交付能力”中移除相关宣传（见第 7 节）。
- 阶段 H：Web/H5 单测扩充、Playwright 关键 E2E、k6/JMeter/wrk 压测、Docker Compose 全栈集成与安全越权端到端演练。

## 7. 文档校准

- `docs/api-spec.yaml`：角色枚举更新为 6 角色；新增 `/auth/logout`、`/auth/csrf`、`/auth/me`、`/auth/password/change`、`/merchant/products`、`/gov/summary`、`/gov/scope`、`/admin/**` 契约；修复 2 处导致 YAML 非法的未加引号标量。
- `deploy/PRODUCTION.md`、`.env.production.example`、`.env.example`：补充环境边界与外部待开通项。
- `README.md`、`docs/architecture.md`、`docs/roadmap.md`、`web/README.md`：补充 2026-09-12 整改说明与演示/生产边界。

## 8. 与验收基线的对照

| 验收项 | 状态 |
|---|---|
| 1. production 缺密钥/域名/存储/支付配置 fail-fast | ✅ 已实现并有单测（`ProductionSafetyValidatorTest` 9 例：演示开关/profile/密钥/库口令/灌数） |
| 2. 生产不创建演示账号/数据 | ✅ 已实现（`@ConditionalOnProperty` + 启动校验） |
| 3. 端点×角色×租户自动化矩阵，越权后端拒绝 | ✅ 已实现（23 用例） |
| 4. 农户不可读治理大盘/全租户订单；政府不可写；商家不可跨租户 | ✅ 已实现 |
| 5. `/b/*` 未登录直达 `/b/login`，过期会话不跳错路径 | ✅ 已实现（`SessionGuard` + `BASE_PATH`） |
| 6. 商家可管理含草稿/下架商品并上下架；图片上传 | ⚠️ 部分（商品状态/上下架已实现；图片上传待阶段 C 后续） |
| 7. H5 只展示真实发布商品与真实媒体 | ⚠️ 部分（真实商品数据可用；真实媒体需对象存储） |
| 8. 下单严格校验店铺/SKU 作用域；支付状态机与幂等测试 | ⚠️ 部分（CAS 防超卖/幂等/关单已保留；`PaymentProvider`+回调待阶段 E） |
| 9. AI 知识上传/删除受 JWT 与角色约束；生产无密钥不伪造 | ✅ 鉴权 + **DEMO_MODE 降级边界**已实现（生产无密钥判 UNAVAILABLE）；知识审核/版本已落库 |
| 10. 政府大屏只读、按授权聚合并显示口径/更新时间 | ✅ 已实现 |
| 11. 大屏事件流来自真实后端，否则生产隐藏 | ⚠️ 部分（模拟卡片已生产默认隐藏并标注“非实时”；真实 SSE/WebSocket 事件流待办） |
| 12. 干净环境一键启动、迁移可重复、全类测试通过 | ⚠️ 部分（backend 138/web/h5 通过；两份 Compose 语法校验通过；容器全栈启动与 E2E 未跑） |
| 13. 仅暴露 80/443、HTTPS/安全头/限流、密钥不进仓库 | ✅ 配置就绪（HTTPS 需证书） |
| 14. OpenAPI/README/roadmap/部署文档与实测一致 | ⚠️ 部分（api-spec 与部署文档已更新；README/roadmap 增补说明） |

## 9. 补充整改（第二批：AI 自配置密钥、知识审核、营销审批、商品域完善）

> 用户指令：支付与媒体接口先保留（不做），其余继续做完；AI 必须保留“自己配置 API Key”的地方。

### 9.1 AI 自配置 API Key（显式需求）

- **唯一入口**：`ai-service/.env.example`（复制为 `ai-service/.env` 后填入您自己的
  `DEEPSEEK_API_KEY` / `EMBEDDING_API_KEY`）；也支持容器环境变量 / 服务器 `export` 注入。
  该文件不含任何真实密钥，`.env` 已被 gitignore。
- **可验证生效**：新增 `GET /ai/v1/capabilities`（需登录）返回 LLM/Embedding 是否已配置、当前模式
  （`REAL`/`MOCK`/`UNAVAILABLE`）与模型名，**绝不回显密钥**（有单测断言密钥不出现在响应中）。
- **就绪判定**：新增 `GET /ai/v1/readyz`（数据库 + 真实 Embedding/LLM 能力 + 知识库基础状态，
  未就绪返回 503 并附 checks）与 `GET /ai/v1/livez`（纯存活）；
  历史 `GET /ai/v1/healthz` 保留以兼容既有探针脚本。
- **演示/生产边界**：`DEMO_MODE=true` 或非生产才允许伪随机 Embedding / 离线营销模板；
  **生产未配置密钥时 Embedder 抛 `EmbeddingUnavailableError`、营销返回 503/A5003**，
  不再伪装成真实 AI（`tests/test_ai_capabilities_unit.py` 11 例覆盖）。

### 9.2 知识库生产化（阶段 F）

- **安全解析**：新增 `app/rag/document_loader.py` —— 扩展名白名单（txt/md/markdown/pdf）、
  5 MiB 大小上限、PDF `%PDF-` 魔数校验、pypdf 缺失时明确提示、PDF ≤200 页、
  NUL 字节/非 UTF-8 拒绝；PDF 按真实页码切片（引用可溯源）。
- **文档登记**：新增 `t_knowledge_doc`（当前版本元数据）+ `t_knowledge_doc_version`（版本历史），
  记录内容 SHA-256、文件大小、页数、切片数、上传者、审核状态与审核人/时间/意见。
- **审核闭环**：`POST /ai/v1/knowledge/docs/review`（VILLAGE/PLATFORM_ADMIN 可审，
  商家可上传不可自审）；`GET /ai/v1/knowledge/docs/versions` 查询版本历史；
  列表接口返回审核状态/版本/哈希/上传者。

### 9.3 营销生成任务持久化与真实审批（阶段 F）

- 新增 `t_marketing_task`（输入/输出 JSON、合规评分快照、审批状态、审批人、发布时间）。
- `POST /ai/v1/marketing/generate` 生成后落库并返回 `taskId`（DB 不可用时降级为 `taskId=null` 并告警）。
- 新增 `GET /ai/v1/marketing/tasks`、`.../approve`、`.../reject`、`.../publish`；
  审批走独立状态机模块 `marketing_review.py`（**仅 APPROVED 可发布**，5 例单测覆盖）。
- B 端 `/b/agents` 新增「营销任务台账与审批」真实组件（通过/驳回/标记发布均调后端 API）。

### 9.4 商品域完善（阶段 C，非媒体）

- Flyway `V6__product_domain_and_order_logistics.sql`：商品分类/单位/产地/详情、库存预警阈值、
  创建人/更新时间；订单承运商/物流单号/发货时间、取消原因、订单查询凭证哈希；
  新增 `t_merchant_profile`（公开店铺 slug，供受控解析租户）。
- 新增 `PUT /api/v1/products/{id}/profile`（扩展资料，作用域同商品写）、
  `GET /api/v1/merchant/products/low-stock`（库存预警）；商品写操作全部写审计日志
  （`PRODUCT_CREATE/UPDATE/STATUS/PROFILE`）。
- **媒体（图片/视频）与对象存储按用户指令保留，未实现**；既有 `imageUrl` 仍为占位地址，待媒体层落地后替换。

### 9.5 第二批测试结果

| 套件 | 命令 | 结果 |
|---|---|---|
| backend | `mvn test` | **140 tests, 0 failures, 0 errors** |
| ai-service（离线） | `pytest tests/ -q --ignore=test_qa_flow.py --ignore=test_rag_hybrid.py` | **35 passed** |
| web | `typecheck` / `lint` / `test` / `build` | 全部通过（新增营销台账组件参与构建） |
| api-spec | `yaml.safe_load` | ✅ 合法，33 条 path |

### 9.6 第二批后仍未完成（不含用户明确保留的支付/媒体）

- H5 交易闭环剩余：购物车/多 SKU、订单中心页、取消/售后、**本人订单查询凭证**（表字段已就绪，接口未实现）、
  隐私政策/用户协议页、演示填充地址按钮的环境开关。
- 政府大屏剩余：CSV/Excel 导出与导出审计、真实 SSE/WebSocket 事件流（当前模拟卡片已生产隐藏）。
- 订单物流：`carrier/tracking_no/shipped_at` 列与实体已就绪，**发货填写物流单号接口未实现**。
- 渠道适配器（抖音/快手/B2B）与移动端（`mobile/`）未实现；Playwright E2E、k6 压测、Compose 全栈启动与安全越权端到端演练未执行。
- RAG 评测集与门槛、来源可核验资料导入流程未建立。

## 10. 第三批（订单闭环、政府导出、渠道适配器、H5 订单中心）

### 10.1 订单闭环（阶段 E，不含支付）

- **物流登记**：`POST /api/v1/orders/{orderNo}/ship` 支持可选请求体
  `{carrier, trackingNo}`，出库同时写入承运商/物流单号/发货时间（不传保持原「一键出库」语义，向后兼容）。
- **主动取消**：新增 `POST /api/v1/orders/{orderNo}/cancel`（OPS 角色），
  STOCK_CONFIRMED 且未支付 → CANCELLED，条件状态门 + **同事务库存回补** + Outbox 事件
  （`MANUAL_CANCEL`）；已支付/已关闭返回 409 + B2003。
- **本人订单查询凭证**：下单响应新增 `queryToken`（明文仅返回一次，库中存 PBKDF2 哈希）；
  新增 `POST /api/v1/orders/guest/lookup` 与 `/guest/cancel`（匿名 + 凭证鉴权），
  返回**脱敏**信息（姓名 `张*`、手机号 `138****00`、地址仅到县/镇），
  凭证错误统一 404，避免订单号枚举。

### 10.2 政府导出（阶段 D）

- 新增 `GET /api/v1/gov/export`：按授权范围导出 UTF-8 BOM CSV（范围元数据 + 合计口径 + 租户下钻），
  并写审计日志 `GOV_EXPORT`（含范围、租户数、字节数）；仅 GOVERNMENT/PLATFORM_ADMIN。

### 10.3 渠道适配器（阶段 G）

- 新增 `ChannelAdapter` / `ConfiguredChannelAdapter` / `ChannelAdapterRegistry` /
  `ChannelSignatureVerifier`（HMAC-SHA256 + 5 分钟时间窗防重放）与 `ChannelProperties`。
- 新增 `POST /api/v1/channels/{channel}/notify`：**默认未配置 → 503 + C5003**；
  签名失败 → 403；签名通过但回流未实现 → 501 + C5004；未知渠道 → 404 + A1004。
  抖音/快手/B2B 全部默认关闭且无密钥，**不宣称已接入**。
- 配置项：`yuzhuang.channel.{douyin,kuaishou,b2b}.{enabled,secret}`（仅环境变量注入）。

### 10.4 H5 消费端（阶段 E）

- 新增**订单中心**：列出本机保存的下单凭证，支持「订单号 + 查询凭证」查询、
  查看状态/物流、**本人取消**（未支付）、清除本机记录；凭证仅存 localStorage，不含个人敏感信息。
- 新增**隐私政策 / 用户协议 / 收货信息处理说明**（页脚折叠区，明确脱敏与凭证机制）。
- **演示内容环境化**：`VITE_DEMO_MODE` 控制「一键填入演示地址」按钮与页脚“演示模式”文案；
  生产构建默认不出现演示按钮。

### 10.5 第三批测试结果

| 套件 | 命令 | 结果 |
|---|---|---|
| backend | `mvn test` | **157 tests, 0 failures, 0 errors**（新增：订单闭环 8、渠道适配器 6+3、商品扩展 2 等） |
| h5 | `npm run typecheck` / `lint` / `test` / `build` | 全部通过（订单中心参与构建） |
| ai-service（离线） | `pytest tests/ -q --ignore=test_qa_flow.py --ignore=test_rag_hybrid.py` | **35 passed** |
| web | `typecheck` / `lint` / `test` / `build` | 全部通过 |
| api-spec | `yaml.safe_load` | ✅ 合法，**38 条 path** |

### 10.6 第三批后仍未完成

- **支付**（按用户指令保留）：`PaymentProvider`、幂等支付单、回调验签、退款/对账。
- **媒体**（按用户指令保留）：对象存储、图片上传/排序/设主图、`imageUrl` 仍为占位地址。
- H5 购物车/多 SKU（当前为单品下单）、订单列表（当前需逐单凭证查询）、售后/退款申请。
- 政府大屏真实 SSE/WebSocket 事件流（模拟卡片仍为生产隐藏状态）。
- RAG 评测集与门槛、来源可核验资料导入流程、Playwright E2E、k6 压测、
  容器全栈启动与安全越权端到端演练、移动端（`mobile/`）。
