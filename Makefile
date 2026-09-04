# 智汇于庄 · 常用命令入口
# 用法：make <target>，例如 make infra-up

COMPOSE      ?= docker compose
COMPOSE_FILE ?= deploy/docker-compose.yml

# ---------- 基础设施（PostgreSQL / Redis / Nginx 网关） ----------

.PHONY: infra-up
infra-up:            ## 启动基础设施
	$(COMPOSE) -f $(COMPOSE_FILE) up -d

.PHONY: infra-down
infra-down:          ## 停止基础设施（保留数据卷）
	$(COMPOSE) -f $(COMPOSE_FILE) down

.PHONY: infra-down-v
infra-down-v:        ## 停止基础设施并清除数据卷（谨慎：会删除数据）
	$(COMPOSE) -f $(COMPOSE_FILE) down -v

.PHONY: infra-logs
infra-logs:          ## 查看基础设施日志
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f

.PHONY: infra-ps
infra-ps:            ## 查看运行状态
	$(COMPOSE) -f $(COMPOSE_FILE) ps

.PHONY: infra-restart
infra-restart:       ## 重启基础设施
	$(COMPOSE) -f $(COMPOSE_FILE) restart

# ---------- 数据库辅助 ----------

.PHONY: db-vector
db-vector:           ## 启用 pgvector 扩展
	$(COMPOSE) -f $(COMPOSE_FILE) exec postgres \
	  psql -U $${POSTGRES_USER:-rural_user} -d $${POSTGRES_DB:-rural_revitalization} \
	  -c "CREATE EXTENSION IF NOT EXISTS vector;"

.PHONY: db-shell
db-shell:            ## 进入 PostgreSQL 交互终端
	$(COMPOSE) -f $(COMPOSE_FILE) exec postgres \
	  psql -U $${POSTGRES_USER:-rural_user} -d $${POSTGRES_DB:-rural_revitalization}

.PHONY: redis-cli
redis-cli:           ## 进入 Redis 交互终端
	$(COMPOSE) -f $(COMPOSE_FILE) exec redis redis-cli

# ---------- 业务服务一键编排（容器化 + 网关接入） ----------

.PHONY: all-up
all-up:            ## 构建并后台启动全部服务（postgres/redis/gateway/backend/ai-service/h5/web）
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build

.PHONY: all-down
all-down:          ## 停止全部服务（保留数据卷）
	$(COMPOSE) -f $(COMPOSE_FILE) down

.PHONY: verify
verify:            ## 端到端验证网关（healthz / backend / ai-service / checkout / marketing）
	bash scripts/verify_gateway.sh

# ---------- 真实业务种子数据 + 全栈集成验证 ----------

.PHONY: seed
seed:              ## 幂等灌入属地化业务数据（3 款在售 SKU / 50 条订单 / 50 条 Outbox）+ RAG 文档向量化
	@echo "==> 应用 deploy/seed-realistic-data.sql（业务数据：3 SKU / 50 订单 / 50 Outbox）..."
	$(COMPOSE) -f $(COMPOSE_FILE) exec -T postgres \
	  psql -U $${POSTGRES_USER:-rural_user} -d $${POSTGRES_DB:-rural_revitalization} \
	  -v ON_ERROR_STOP=1 < deploy/seed-realistic-data.sql
	@echo "==> RAG 文档向量化入库（scripts/seed_rag.sh）..."
	bash scripts/seed_rag.sh

.PHONY: seed-db-only
seed-db-only:      ## 仅灌入业务数据（3 SKU / 50 订单 / Outbox），不触发文档向量化
	$(COMPOSE) -f $(COMPOSE_FILE) exec -T postgres \
	  psql -U $${POSTGRES_USER:-rural_user} -d $${POSTGRES_DB:-rural_revitalization} \
	  -v ON_ERROR_STOP=1 < deploy/seed-realistic-data.sql

.PHONY: seed-rag
seed-rag:          ## 仅执行 RAG 文档向量化入库（幂等）
	bash scripts/seed_rag.sh

.PHONY: verify-all
verify-all:        ## 全栈 8 项集成验证（网关/后端/AI/两前端/商品列表/下单闭环/RAG 问答）
	bash scripts/verify_all_stack.sh

# ---------- C 端轻量 H5（Vite + React，静态托管于容器内 nginx） ----------

.PHONY: h5-build
h5-build:          ## 本地类型检查构建 + 打包 H5 镜像
	cd h5 && npm run build
	$(COMPOSE) -f $(COMPOSE_FILE) build h5

.PHONY: h5-up
h5-up:             ## 构建并后台启动 H5 + 网关（访问 http://localhost/h5/）
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build h5 gateway

.PHONY: h5-down
h5-down:           ## 停止 H5（保留数据卷）
	$(COMPOSE) -f $(COMPOSE_FILE) stop h5

.PHONY: h5-logs
h5-logs:           ## 查看 H5 容器日志
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f h5

# ---------- B 端中枢大脑（Next.js PC 数据大屏，经网关 /b/ 对外） ----------

.PHONY: web-build
web-build:         ## 本地类型检查构建 + 打包 B 端 web 镜像
	cd web && npm run build
	$(COMPOSE) -f $(COMPOSE_FILE) build web

.PHONY: web-up
web-up:            ## 构建并后台启动 B 端 web + 网关（访问 http://localhost/b/）
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build web gateway

.PHONY: web-down
web-down:          ## 停止 B 端 web（保留数据卷）
	$(COMPOSE) -f $(COMPOSE_FILE) stop web

.PHONY: web-logs
web-logs:          ## 查看 B 端 web 容器日志
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f web

# ---------- 帮助 ----------

.PHONY: help
help:                ## 显示帮助
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
