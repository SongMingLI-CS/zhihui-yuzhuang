# ============================================================
# 智汇于庄 backend · Spring Boot 容器镜像（多阶段构建）
#
#   Builder : maven:3.9-eclipse-temurin-21 —— 编译打包（跳过测试）
#   Runner  : eclipse-temurin:21-jre        —— 精简 Java 21 运行时
#
# 说明：
#   1. Runner 刻意不用 alpine/musl 变体：backend 需经 Docker 内嵌 DNS
#      (127.0.0.11) 解析 postgres / redis 服务名，完整 glibc JRE 的
#      DNS 兼容性更稳，避免偶发解析失败导致启动探活抖动。
#   2. 以 UID 65534 (nobody) 非 root 运行，EXPOSE 8080。
#   3. JVM 容器感知内存参数：UseContainerSupport + MaxRAMPercentage，
#      自动按容器限额分配堆，无需手工固定 -Xmx。
# ============================================================

# ---------- 阶段一：Builder ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先单独拷贝 pom.xml，利用 Docker layer 缓存依赖下载（业务代码变更不触发重下依赖）
COPY pom.xml ./
RUN mvn -B dependency:go-offline -q || true

# 再拷贝源码并打包（产出可执行 fat jar backend-*.jar）
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 阶段二：Runner ----------
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="yuzhuang-backend" \
      org.opencontainers.image.description="智汇于庄 · Spring Boot 高并发业务后端"

ENV TZ=Asia/Shanghai \
    APP_HOME=/app \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# 时区数据、curl（健康检查探针）、ca-certificates（外部 HTTPS 依赖可选）
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata curl ca-certificates \
    && ln -snf /usr/share/zoneinfo/${TZ} /etc/localtime \
    && echo ${TZ} > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

WORKDIR ${APP_HOME}

# 从 Builder 阶段复制可执行 jar
COPY --from=builder /build/target/backend-*.jar ${APP_HOME}/app.jar

# 非 root 运行：nobody (65534)
RUN chown -R 65534:65534 ${APP_HOME}
USER 65534:65534

EXPOSE 8080

# 容器健康检查：探测 backend 自有的 /api/v1/healthz（含真实 Postgres SELECT 1）
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:${SERVER_PORT}/api/v1/healthz >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar ${APP_HOME}/app.jar"]
