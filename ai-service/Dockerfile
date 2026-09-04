# 智汇于庄 · ai-service 镜像
# FastAPI 大模型 / RAG 服务，加入 rural-network，经网关 /ai/* 对外。
# 运行：在 ai-service 目录执行
#   docker build -t yuzhuang-ai-service:latest .

FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 基础工具（curl 供健康检查探针使用）
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 先装依赖，充分利用镜像层缓存
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

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
