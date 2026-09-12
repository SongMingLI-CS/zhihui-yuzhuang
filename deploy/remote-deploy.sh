#!/usr/bin/env bash
# ============================================================
# 智汇于庄 · 云服务器一键部署脚本（在【目标 Linux 服务器】上执行）
#
# 用法（在项目根目录，或任意位置——脚本会自动定位仓库根）：
#   bash deploy/remote-deploy.sh
#   GATEWAY_PORT=8080 WITH_SEED=1 POSTGRES_PASSWORD='StrongPwd!' bash deploy/remote-deploy.sh
#
# 特性：幂等可重复执行；不会删除 postgres_data / redis_data 数据卷。
# 契约：deploy/docker-compose.yml + deploy/nginx/nginx.conf + docs/api-spec.yaml
#
# 可覆盖的环境变量：
#   COMPOSE_FILE        默认 deploy/docker-compose.yml
#   ENV_FILE            默认 <仓库根>/.env（缺失时由 .env.example 生成）
#   GATEWAY_PORT        网关对外端口，默认 80
#   POSTGRES_PASSWORD   数据库密码，默认 rural_password（生产必须覆盖）
#   DEEPSEEK_API_KEY    大模型 Key（可空 → ai-service 自动降级）
#   EMBEDDING_API_KEY   向量化 Key（可空 → 内置降级/Mock）
#   WITH_SEED=1         部署完成后灌入业务种子数据 + RAG 向量化
#   NO_MIRROR=1         跳过 Docker 镜像加速器自动配置
#   EXTRA_COMPOSE_FILES 叠加编排文件（":" 分隔），如 deploy/docker-compose.mirror.yml
#   VERIFY_WAIT         网关就绪等待上限秒，默认 300
# ============================================================
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

COMPOSE_FILE="${COMPOSE_FILE:-deploy/docker-compose.yml}"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
GATEWAY_PORT="${GATEWAY_PORT:-80}"
WITH_SEED="${WITH_SEED:-0}"
NO_MIRROR="${NO_MIRROR:-0}"
VERIFY_WAIT="${VERIFY_WAIT:-300}"

# 可选叠加编排层（例如国内网络的构建加速层）：
#   EXTRA_COMPOSE_FILES=deploy/docker-compose.mirror.yml bash deploy/remote-deploy.sh
# 注意：`docker compose -f a:b` 不会按 ":" 拆分多文件（实测报 no such file），
# 必须展开为多个 -f 参数，故此处组装 COMPOSE_ARGS 数组。
COMPOSE_FILES="${COMPOSE_FILE}"
if [ -n "${EXTRA_COMPOSE_FILES:-}" ]; then
    COMPOSE_FILES="${COMPOSE_FILES}:${EXTRA_COMPOSE_FILES}"
fi
COMPOSE_ARGS=()
IFS=':' read -r -a _compose_file_list <<< "${COMPOSE_FILES}"
for _f in "${_compose_file_list[@]}"; do
    [ -n "${_f}" ] && COMPOSE_ARGS+=(-f "${_f}")
done

# docker compose 必须以「数组」保存再展开（"${COMPOSE_CMD[@]}"）：按字符串保存并加引号
# 执行会把 "docker compose" 当成单个命令名 → command not found（rc=127）。
# 约定沿用 scripts/seed_rag.sh。
COMPOSE_CMD=(docker compose)
if [[ -n "${COMPOSE:-}" ]]; then
    read -r -a COMPOSE_CMD <<< "${COMPOSE}"
fi

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    if command -v sudo >/dev/null 2>&1; then SUDO="sudo"; fi
fi

if [ -t 1 ]; then
    GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; NC=$'\033[0m'
else
    GREEN=''; RED=''; YELLOW=''; BOLD=''; NC=''
fi
say()  { printf '%s\n' "$*"; }
ok()   { printf '%s[ OK ]%s %s\n' "${GREEN}" "${NC}" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "${YELLOW}" "${NC}" "$*"; }
die()  { printf '%s[FAIL]%s %s\n' "${RED}" "${NC}" "$*" >&2; exit 1; }

# 统一注入 --env-file：Compose v2 的 .env 取自「项目目录」（即 compose 文件所在目录
# deploy/），放在仓库根的 .env 必须显式指定，否则密码等变量会静默回落默认值。
run_compose() { "${COMPOSE_CMD[@]}" --env-file "${ENV_FILE}" "${COMPOSE_ARGS[@]}" "$@"; }

env_get() { sed -n -E "s|^$1=(.*)$|\1|p" "${ENV_FILE}" | tail -n 1 | tr -d '\r'; }

echo "${BOLD}==> 智汇于庄 · 云服务器部署 @ $(hostname) [${ROOT_DIR}]${NC}"

# ---------- 0) 前置检查 ----------
echo
echo "${BOLD}[0/6] 前置检查${NC}"
[ -f "${COMPOSE_FILE}" ] || die "未找到编排文件 ${COMPOSE_FILE}，请确认本脚本位于仓库 deploy/ 目录内。"
command -v docker >/dev/null 2>&1 || die "未检测到 docker，请先安装 Docker Engine：curl -fsSL https://get.docker.com | sh"
docker info >/dev/null 2>&1 || die "Docker 守护进程不可用，请先启动：${SUDO} systemctl enable --now docker"
"${COMPOSE_CMD[@]}" version >/dev/null 2>&1 || die "未检测到 Docker Compose v2 插件（docker compose），请安装 docker-compose-plugin。"
ok "$("${COMPOSE_CMD[@]}" version --short 2>/dev/null || docker --version)"
AVAIL_KB="$(df -Pk "${ROOT_DIR}" | awk 'NR==2 {print $4}')"
if [ "${AVAIL_KB:-0}" -lt 8388608 ]; then
    warn "磁盘可用空间仅 $(( ${AVAIL_KB:-0} / 1024 ))MB（镜像构建建议预留 ≥ 8GB）"
else
    ok "磁盘可用空间 $(( AVAIL_KB / 1024 / 1024 ))GB"
fi

# 内存与 swap：小内存主机并行构建（Maven + Next.js + npm + pip 同时进行）易触发 OOM。
# 低于 6GB 时收敛为串行构建（COMPOSE_PARALLEL_LIMIT=1）；无 swap 时给出告警
# （不自动改动宿主机 swap 配置，避免越界修改系统）。
MEM_TOTAL_KB="$(awk '/MemTotal/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
SWAP_TOTAL_KB="$(awk '/SwapTotal/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
if [ "${MEM_TOTAL_KB:-0}" -gt 0 ] && [ "${MEM_TOTAL_KB}" -lt 6291456 ]; then
    export COMPOSE_PARALLEL_LIMIT=1
    warn "内存 $(( MEM_TOTAL_KB / 1024 / 1024 ))GB < 6GB：已设 COMPOSE_PARALLEL_LIMIT=1 串行构建以防 OOM"
else
    ok "内存 $(( ${MEM_TOTAL_KB:-0} / 1024 / 1024 ))GB"
fi
if [ "${SWAP_TOTAL_KB:-0}" -eq 0 ]; then
    warn "未启用 swap：内存吃紧时缺少缓冲，建议为小内存实例配置 2GB swap"
else
    ok "swap $(( SWAP_TOTAL_KB / 1024 / 1024 ))GB"
fi

# 行尾规范化：本仓库在 Windows 工作区（core.autocrlf=true、无 .gitattributes）内
# *.sh / Makefile 均为 CRLF；经 scp/tar 上传到 Linux 后执行会报
# "set: pipefail: invalid option name" 之类的错误（已实测复现）。
# 这里对 deploy/ scripts/ Makefile 与 .env* 做幂等规范化（Docker Compose 自身可容忍
# .env 的 CRLF，但 bash 不能，故统一收敛为 LF）。
mapfile -t CRLF_FILES < <(
    grep -rlIU $'\r' \
        "${ROOT_DIR}/.env" "${ROOT_DIR}/.env.example" "${ROOT_DIR}/Makefile" \
        "${ROOT_DIR}/deploy" "${ROOT_DIR}/scripts" 2>/dev/null || true
)
if [ "${#CRLF_FILES[@]}" -gt 0 ]; then
    warn "检测到 ${#CRLF_FILES[@]} 个 CRLF 文本文件（Windows 工作区特征），规范化为 LF..."
    if command -v dos2unix >/dev/null 2>&1; then
        dos2unix -q "${CRLF_FILES[@]}"
    else
        sed -i 's/\r$//' "${CRLF_FILES[@]}"
    fi
    ok "行尾规范化完成（Makefile / *.sh / nginx.conf / *.sql / .env*）"
else
    ok "行尾均为 LF，无需规范化"
fi

# ---------- 1) Docker 镜像加速器 ----------
echo
echo "${BOLD}[1/6] 镜像源连通性${NC}"
HUB_CODE="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 https://registry-1.docker.io/v2/ 2>/dev/null || true)"
if [ -n "${HUB_CODE}" ] && [ "${HUB_CODE}" != "000" ]; then
    ok "Docker Hub 可达（HTTP ${HUB_CODE}），无需加速器"
elif [ "${NO_MIRROR}" = "1" ]; then
    warn "Docker Hub 不可达，但 NO_MIRROR=1 已指定跳过自动配置"
elif [ -z "${SUDO}" ] && [ "$(id -u)" -ne 0 ]; then
    warn "Docker Hub 不可达，但当前用户无 root/sudo，无法写入 /etc/docker/daemon.json"
    warn "请以 root 重跑，或手工添加 registry-mirrors（参见 deploy/DEPLOY-REMOTE.md）"
else
    warn "Docker Hub 不可达，正在写入镜像加速器并重启 Docker..."
    DAC="/etc/docker/daemon.json"
    ${SUDO} mkdir -p /etc/docker
    if [ -f "${DAC}" ]; then
        ${SUDO} cp -n "${DAC}" "${DAC}.bak.$(date +%Y%m%d%H%M%S)" || true
    fi
    if command -v python3 >/dev/null 2>&1; then
        ${SUDO} python3 - "${DAC}" <<'PY'
import json, os, sys
path = sys.argv[1]
cfg = {}
if os.path.exists(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            cfg = json.load(fh) or {}
    except Exception:
        cfg = {}
cfg["registry-mirrors"] = [
    "https://mirror.ccs.tencentyun.com",
    "https://docker.m.daocloud.io",
    "https://docker.nju.edu.cn",
    "https://docker.1ms.run",
]
with open(path, "w", encoding="utf-8") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("daemon.json updated:", path)
PY
    elif [ -f "${DAC}" ]; then
        warn "无 python3 且 ${DAC} 已存在：已跳过自动合并，请手工添加 registry-mirrors"
    else
        ${SUDO} tee "${DAC}" >/dev/null <<'JSON'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.nju.edu.cn",
    "https://docker.1ms.run"
  ]
}
JSON
        ok "已写入 ${DAC}"
    fi
    ${SUDO} systemctl restart docker 2>/dev/null || warn "systemctl restart docker 失败，请手动重启 Docker"
    for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
    docker info >/dev/null 2>&1 && ok "Docker 已重启且可用" || die "Docker 重启后仍不可用，请检查 /etc/docker/daemon.json"
fi

# ---------- 2) .env 生成 / 覆盖 ----------
echo
echo "${BOLD}[2/6] 环境变量文件${NC}"
if [ ! -f "${ENV_FILE}" ]; then
    [ -f .env.example ] || die "缺少 .env.example，无法生成 ${ENV_FILE}"
    cp .env.example "${ENV_FILE}"
    ok "已由 .env.example 生成 ${ENV_FILE}"
else
    ok "复用既有 ${ENV_FILE}"
fi

set_env_kv() {
    local key="$1" value="${2:-}"
    [ -n "${value}" ] || return 0
    if grep -qE "^${key}=" "${ENV_FILE}"; then
        # 以 | 作 sed 分隔符，避免密码中的 / 破坏替换
        sed -i.bak -E "s|^${key}=.*$|${key}=${value}|" "${ENV_FILE}" && rm -f "${ENV_FILE}.bak"
    else
        printf '%s=%s\n' "${key}" "${value}" >> "${ENV_FILE}"
    fi
}
set_env_kv GATEWAY_PORT "${GATEWAY_PORT}"
set_env_kv POSTGRES_PASSWORD "${POSTGRES_PASSWORD:-}"
set_env_kv DEEPSEEK_API_KEY "${DEEPSEEK_API_KEY:-}"
set_env_kv EMBEDDING_API_KEY "${EMBEDDING_API_KEY:-}"
chmod 600 "${ENV_FILE}" 2>/dev/null || true

PG_USER="$(env_get POSTGRES_USER)"; PG_USER="${PG_USER:-rural_user}"
PG_DB="$(env_get POSTGRES_DB)"; PG_DB="${PG_DB:-rural_revitalization}"
ok "网关端口=${GATEWAY_PORT} / 数据库 ${PG_USER}@${PG_DB} / 时区=$(env_get TZ)"

# ---------- 3) 端口占用检查 ----------
echo
echo "${BOLD}[3/6] 端口占用检查${NC}"
port_busy() {
    if command -v ss >/dev/null 2>&1; then
        ss -lnt 2>/dev/null | awk 'NR>1 {print $4}' | grep -qE "[:.]$1$"
    else
        netstat -lnt 2>/dev/null | awk 'NR>2 {print $4}' | grep -qE "[:.]$1$"
    fi
}
for p in "${GATEWAY_PORT}" 5433 6379; do
    if port_busy "${p}"; then
        if docker ps --format '{{.Ports}}' 2>/dev/null | grep -qE "[:.]${p}->"; then
            warn "端口 ${p} 已被本机 Docker 容器占用（若为本项目容器，部署会自动收敛）"
        else
            warn "端口 ${p} 被非本项目进程占用；如冲突请显式指定 GATEWAY_PORT/POSTGRES_PORT/REDIS_PORT 后重跑"
        fi
    else
        ok "端口 ${p} 空闲"
    fi
done

# ---------- 4) 构建并启动全部服务 ----------
echo
echo "${BOLD}[4/6] 构建镜像并启动全部服务（postgres/redis/gateway/backend/ai-service/web/h5）${NC}"
say "    首次构建需拉取基础镜像与依赖，耗时较长，请耐心等待..."
if ! run_compose up -d --build; then
    warn "构建或启动失败，最近日志如下："
    run_compose logs --tail 60 || true
    die "部署失败：docker compose up -d --build 未成功"
fi
run_compose ps

# ---------- 5) 就绪等待 + 网关端到端验证 ----------
echo
echo "${BOLD}[5/6] 服务就绪等待（上限 ${VERIFY_WAIT}s）${NC}"
waited=0
while :; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 3 --max-time 5 "http://127.0.0.1:${GATEWAY_PORT}/healthz" 2>/dev/null || true)"
    if [ "${code}" = "200" ]; then
        break
    fi
    if [ "${waited}" -ge "${VERIFY_WAIT}" ]; then
        echo
        warn "网关 ${VERIFY_WAIT}s 内未就绪，最近日志如下："
        run_compose logs --tail 60 gateway backend ai-service || true
        die "部署失败：网关未就绪"
    fi
    printf '.'
    sleep 5
    waited=$((waited + 5))
done
echo
ok "网关 /healthz 已就绪（约 ${waited}s）"

if GATEWAY_HOST=127.0.0.1 GATEWAY_PORT="${GATEWAY_PORT}" bash scripts/verify_gateway.sh; then
    ok "网关端到端验证通过"
else
    warn "网关端到端验证存在失败项，请查看上方输出与容器日志："
    run_compose logs --tail 40 backend ai-service || true
    exit 1
fi

# ---------- 6) 可选：业务种子数据 + RAG 向量化 ----------
echo
if [ "${WITH_SEED}" = "1" ]; then
    echo "${BOLD}[6/6] 灌入业务种子数据 + RAG 向量化${NC}"
    run_compose exec -T postgres psql -U "${PG_USER}" -d "${PG_DB}" -v ON_ERROR_STOP=1 \
        < deploy/seed-realistic-data.sql
    bash scripts/seed_rag.sh
    ok "种子数据与知识库向量化完成"
else
    echo "${BOLD}[6/6] 跳过种子数据（如需灌入请加 WITH_SEED=1 重跑）${NC}"
fi

# ---------- 汇总 ----------
HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "${HOST_IP}" ] || HOST_IP="<服务器地址>"
echo
echo "----------------------------------------------"
printf '%s%s[部署完成] 智汇于庄已上线%s\n' "${GREEN}" "${BOLD}" "${NC}"
echo "  网关状态页   : http://${HOST_IP}:${GATEWAY_PORT}/"
echo "  C 端 H5      : http://${HOST_IP}:${GATEWAY_PORT}/h5/"
echo "  B 端数据大屏 : http://${HOST_IP}:${GATEWAY_PORT}/b/"
echo "  业务后端 API : http://${HOST_IP}:${GATEWAY_PORT}/api/v1/healthz"
echo "  AI/RAG 服务  : http://${HOST_IP}:${GATEWAY_PORT}/ai/v1/healthz"
echo
say "运维命令（在 ${ROOT_DIR} 执行）："
say "  查看状态  ${COMPOSE_CMD[*]} --env-file ${ENV_FILE} ${COMPOSE_ARGS[*]} ps"
say "  查看日志  ${COMPOSE_CMD[*]} --env-file ${ENV_FILE} ${COMPOSE_ARGS[*]} logs -f"
say "  停止服务  ${COMPOSE_CMD[*]} --env-file ${ENV_FILE} ${COMPOSE_ARGS[*]} down"
say "  端到端验证 bash scripts/verify_gateway.sh"
warn "若为云主机，请确认安全组/防火墙已放行 TCP ${GATEWAY_PORT}（80）。"
