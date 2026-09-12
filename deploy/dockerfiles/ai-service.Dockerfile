# ============================================================
# 智汇于庄 · ai-service 镜像（国内镜像源加速版，部署专用）
#
# 与 ai-service/Dockerfile 功能完全等价（相同基础镜像、依赖、健康检查、启动命令），
# 仅额外做两件事：
#   1) apt 源切到腾讯云内网镜像（deb.debian.org 实测仅 32 KB/s → 腾讯云 18.9 MB/s）
#   2) pip 源切到腾讯云 PyPI 镜像（pypi.org 实测仅 49 KB/s → 腾讯云 856 KB/s）
# 两处均带「失败自动回退官方源」逻辑，镜像源不可用时不会导致构建中断。
#
# 启用方式：由 deploy/docker-compose.mirror.yml 通过 build.dockerfile 指向本文件，
# ai-service/ 模块本身零改动。
# ============================================================

FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 基础工具（curl 供健康检查探针使用）；apt 源优先走腾讯云镜像，失败则回退官方源
RUN set -eux; \
    if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
        SP=/etc/apt/sources.list.d/debian.sources; \
    else \
        SP=/etc/apt/sources.list; \
    fi; \
    cp -a "${SP}" /tmp/apt-sources.orig; \
    sed -i 's|http://deb.debian.org|https://mirrors.cloud.tencent.com|g' "${SP}"; \
    if ! apt-get update; then \
        echo '[mirror] 腾讯云 apt 镜像不可用，回退官方源'; \
        cp -a /tmp/apt-sources.orig "${SP}"; \
        apt-get update; \
    fi; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*

# 先装依赖，充分利用镜像层缓存；pip 优先走腾讯云镜像，失败则回退官方源
COPY requirements.txt ./
RUN pip install --no-cache-dir \
        -i https://mirrors.cloud.tencent.com/pypi/simple \
        --trusted-host mirrors.cloud.tencent.com \
        -r requirements.txt \
    || pip install --no-cache-dir -r requirements.txt

# 拷贝应用代码
COPY app ./app

# 拷贝脚本与文档语料（scripts/ingest_docs.py + data/raw_docs），
# 使 `docker compose exec ai-service python scripts/ingest_docs.py ...`
# 可在容器内直接执行（供 `make seed` 向量化入库，无需再 docker cp）。
COPY scripts ./scripts
COPY data ./data

# 容器内默认通过环境变量注入（见 deploy/docker-compose.yml），此处仅给出本地兜底值，
# 便于脱离编排直接 `docker run` 联调本机 postgres。
ENV DATABASE_URL=postgresql://rural_user:rural_password@host.docker.internal:5432/rural_revitalization

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/ai/v1/healthz || exit 1

# 单 worker + 事件循环，便于与异步连接池配合
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
