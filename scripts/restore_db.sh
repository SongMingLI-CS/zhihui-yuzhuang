#!/usr/bin/env bash
# ============================================================
# 智汇于庄 · PostgreSQL 恢复脚本（阶段 A）
#
# 用法：
#   bash scripts/restore_db.sh <备份文件.dump.gz> [compose文件]
#   # 非交互确认，用于自动化演练：RESTORE_YES=1 bash scripts/restore_db.sh ...
#
# 步骤：
#   1) 校验备份文件（gzip -t）；
#   2) 停业务服务（backend / ai-service / web / h5），保留 postgres/redis/gateway；
#   3) DROP + CREATE 数据库（清空现有 schema，避免残留表冲突）；
#   4) pg_restore 恢复；
#   5) 提示逐项健康检查。
#
# 警告：本操作会覆盖现有数据，请务必先执行 backup_db.sh。
# ============================================================
set -euo pipefail

DUMP_FILE="${1:?用法: bash scripts/restore_db.sh <backup.dump.gz> [compose文件]}"
COMPOSE_FILE="${2:-deploy/docker-compose.prod.yml}"
COMPOSE="${COMPOSE:-docker compose}"
DB_USER="${POSTGRES_USER:-rural_user}"
DB_NAME="${POSTGRES_DB:-rural_revitalization}"

if [[ ! -f "${DUMP_FILE}" ]]; then
  echo "[error] 备份文件不存在：${DUMP_FILE}" >&2
  exit 1
fi

echo "==> 校验备份文件"
gzip -t "${DUMP_FILE}"
DECOMPRESSED="$(mktemp /tmp/yuzhuang-restore-XXXXXX.dump)"
trap 'rm -f "${DECOMPRESSED}"' EXIT
gunzip -c "${DUMP_FILE}" > "${DECOMPRESSED}"

if [[ "${RESTORE_YES:-0}" != "1" ]]; then
  read -r -p "将覆盖数据库 ${DB_NAME}，确认继续？(yes/N) " CONFIRM
  [[ "${CONFIRM}" == "yes" ]] || { echo "已取消"; exit 1; }
fi

echo "==> 停止业务服务（保留 postgres/redis/gateway）"
${COMPOSE} -f "${COMPOSE_FILE}" stop backend ai-service web h5 || true

echo "==> 重建空库 ${DB_NAME}"
${COMPOSE} -f "${COMPOSE_FILE}" exec -T postgres \
  psql -U "${DB_USER}" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS ${DB_NAME} WITH (FORCE);" \
  -c "CREATE DATABASE ${DB_NAME};"

echo "==> pg_restore 恢复"
${COMPOSE} -f "${COMPOSE_FILE}" exec -T postgres \
  pg_restore -U "${DB_USER}" -d "${DB_NAME}" --no-owner --no-privileges --clean --if-exists \
  < "${DECOMPRESSED}"

echo "==> 重启业务服务"
${COMPOSE} -f "${COMPOSE_FILE}" up -d backend ai-service web h5

echo "==> 完成。请依次验证："
echo "    curl -fsS http://localhost/healthz"
echo "    curl -fsS http://localhost/api/v1/healthz"
echo "    curl -fsS http://localhost/ai/v1/healthz"
echo "    Flyway 版本应等于部署前版本（SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;）"
