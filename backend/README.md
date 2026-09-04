# backend · Spring Boot 高并发业务后端

承载用户、商品、订单、库存等核心业务，作为三端与渠道订单的**聚合调度中台**。
详细设计见 [`docs/architecture.md`](../docs/architecture.md) 第 4 节。

## 技术栈（规划）

- Java 17 + Spring Boot 3.x
- Spring Web / Spring Security (JWT) / Spring Data JPA 或 MyBatis-Plus
- PostgreSQL（业务数据）+ pgvector（向量查询走 ai-service，后端不直连向量）
- Redis（缓存 / 会话 / 分布式锁 / Streams）
- Springdoc OpenAPI、Lombok、MapStruct、Flyway（可选）

## 领域模块（建议分包）

```text
src/main/java/com/yuzhuang/
├── common/          # 通用工具、统一响应、全局异常、分布式锁
├── security/        # JWT 认证、RBAC 权限
├── tenant/          # 租户上下文与 Tenant ID 行级隔离
├── user/            # 用户 / 农户 / 合作社 / 村委
├── product/         # 商品、SKU、价格、促销
├── order/           # 订单、渠道订单聚合、B2B 集采
├── inventory/       # 库存、CAS 乐观锁扣减
├── outbox/          # Outbox 发件箱（可靠事件投递）
├── channel/         # 渠道接入抽象（H5 / 抖音 / 快手 / B2B）
└── notification/    # 站内信、模板消息、物流回调
```

## 核心能力规划

| 能力 | 说明 | 状态 |
|------|------|------|
| 多租户隔离 | `tenant_id` 行级隔离 + Redis Key 前缀 | 规划中 |
| 高并发订单 | Redis Streams 削峰、异步落库 | 规划中 |
| 防超卖 | CAS 乐观锁 `stock >= n` 条件更新 | 规划中 |
| 事件可靠性 | Outbox 发件箱 + 后台可靠投递 | 规划中 |
| 渠道聚合 | `OrderSource` + `ChannelAdapter` 适配器 | 规划中 |
| API 文档 | Springdoc OpenAPI | 规划中 |

## 端口约定

- 本地开发：`8080`
- 容器内：`8080`（加入 `rural-network`，经网关 `/api/*` 对外）

## 当前脚手架（契约基石，已落地）

```text
src/main/java/com/yuzhuang/
├── BackendApplication.java     # Spring Boot 入口
├── common/
│   ├── api/ApiResponse.java    # 统一响应：code/message/data/timestamp/requestId
│   ├── context/TenantContext.java   # 租户线程上下文（X-Tenant-Id，缺省 global）
│   ├── enums/ResultCode.java        # 错误码枚举（00000 / A1001-A1004 / B2001-B2003 / C5001-C5002）
│   ├── exception/BusinessException.java
│   ├── constant/HeaderNames.java    # X-Request-Id / X-Tenant-Id / X-Idempotency-Key
│   └── trace/TraceContext.java      # 基于 MDC 的链路上下文
├── web/
│   ├── filter/TraceIdFilter.java    # 链路 ID：透传/生成 + MDC + 响应头
│   ├── filter/TenantContextFilter.java
│   └── exception/GlobalExceptionHandler.java  # BusinessException/校验异常/系统兜底
└── health/
    ├── HealthController.java   # GET /api/v1/healthz（DB SELECT 1）
    ├── HealthService.java
    └── DbHealthService.java
```

## 快速开始

```bash
# 编译（Java 17+；本机需 JDK 17~23，Spring Boot 3.3 官方支持至 JDK 23）
JAVA_HOME=<path-to-jdk17-23> mvn clean test-compile

# 运行测试（test profile 使用 H2 内存库，无需本地 PostgreSQL）
JAVA_HOME=<path-to-jdk17-23> mvn test

# 本地启动（dev profile：localhost:5433；见 .env.example / application-dev.yml）
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

健康检查：`curl http://localhost:8080/api/v1/healthz`
接口文档：`http://localhost:8080/swagger-ui.html`

## 数据源与容器环境感知

- 主配置默认走容器网络：`rural-revitalization-postgres:5432`（可被环境变量
  `POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB/POSTGRES_USER/POSTGRES_PASSWORD` 整体覆盖）；
- `dev` profile 默认宿主 `localhost:5433`（宿主 5432 被占用时的映射端口）；
- 测试用 H2（PostgreSQL 兼容模式），仅 test 作用域，避免本地无库时阻塞集成测试。
