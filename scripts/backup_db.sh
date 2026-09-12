#!/usr/bin/env bash
# ============================================================
# 智汇于庄 · PostgreSQL 备份脚本（阶段 A）
#
# 用法：
#   bash scripts/backup_db.sh [备份目录] [compose文件]
# 默认：备份目录 ./backups，compose文件 deploy/docker-compose.prod.yml
# 环境变量：POSTGRES_USER / POSTGRES_DB / POSTGRES_PASSWORD（或经 --env-file）
#
# 特性：pg_dump 自定义格式（-Fc，支持并行/选择性恢复），gzip 压缩，保留最近 14 份。
# ============================================================
set -euo pipefail

BACKUP_DIR="${1:-./backups}"
COMPOSE_FILE="${2:-deploy/docker-compose.prod.yml}"
KEEP="${BACKUP_KEEP:-14}"

COMPOSE="${COMPOSE:-docker compose}"
DB_USER="${POSTGRES_USER:-rural_user}"
DB_NAME="${POSTGRES_DB:-rural_revitalization}"

mkdir -p "${BACKUP_DIR}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${BACKUP_DIR}/yuzhuang-${DB_NAME}-${STAMP}.dump.gz"

echo "==> 备份 ${DB_NAME} -> ${OUT}"
if ! ${COMPOSE} -f "${COMPOSE_FILE}" ps postgres >/dev/null 2>&1; then
  echo "[warn] 未检测到 compose 文件 ${COMPOSE_FILE}，尝试默认 compose"
  COMPOSE_FILE="deploy/docker-compose.yml"
fi

${COMPOSE} -f "${COMPOSE_FILE}" exec -T postgres \
  pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc --no-owner --no-privileges \
  | gzip -9 > "${OUT}"

echo "==> 校验备份可读（gzip -t）"
gzip -t "${OUT}"

echo "==> 清理历史备份（保留最近 ${KEEP} 份）"
ls -1t "${BACKUP_DIR}"/yuzhuang-*.dump.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "==> 完成：${OUT}"
ls -lh "${OUT}"
