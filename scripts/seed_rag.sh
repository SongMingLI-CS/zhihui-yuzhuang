#!/usr/bin/env bash
# ============================================================
# 智汇于庄 · RAG 知识文档向量化入库（供 `make seed` 调用）
#
# 作用：把 ai-service/data/raw_docs/ 下的属地化农技/政策文档切片、向量化
#       写入 t_knowledge_chunk（租户 tenant_yuzhuang_001）。
#
# 设计要点：
#   1) ingest_docs.py 单次运行只支持一个 --category，且按目录整体发现文档；
#      因此这里把「每个文件」单独 stage 到容器内临时目录，按 (文件名, 分类)
#      分组逐次调用，保证 luyi_wheat_pest_control_2026 -> DISEASE_PEST、
#      luyi_subsidy_policy_2026 -> POLICY 各归其类，不会全被标成同一分类。
#   2) 幂等：入库前先按 doc_title 清理该租户（以及历史遗留的 global）下的
#      旧切片，重复执行 make seed 不会重复累积、也不会与运行期嵌入模式混用。
#   3) 兼容旧镜像（未含 scripts/ data/ 层）：容器内若缺文件，自动 docker compose cp
#      补入，无需强制 rebuild；重建过镜像则直接走 /app 内文件。
#
# 依赖容器在跑：make seed 前请先 make all-up。
#   环境变量：COMPOSE_FILE(默认 deploy/docker-compose.yml) / COMPOSE /
#             TENANT_ID / POSTGRES_USER / POSTGRES_DB
# ============================================================
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/docker-compose.yml}"

# docker compose 命令必须以「数组」形式保存并展开（"${COMPOSE_CMD[@]}"）。
# 若按字符串保存并加引号执行（"${COMPOSE}"），"docker compose" 会被 bash
# 当成单个命令名 → command not found（rc=127），进而误报「服务未运行」。
COMPOSE_CMD=(docker compose)
if [[ -n "${COMPOSE:-}" ]]; then
    # 允许外部覆盖，例如：COMPOSE="docker-compose" 或 COMPOSE="docker /opt/bin/compose"
    read -r -a COMPOSE_CMD <<< "${COMPOSE}"
fi

TENANT_ID="${TENANT_ID:-tenant_yuzhuang_001}"
PG_USER="${POSTGRES_USER:-rural_user}"
PG_DB="${POSTGRES_DB:-rural_revitalization}"

# 映射：源文档名(容器内路径) -> 知识分类
#   仅处理本次交付的两份属地化文档；yuzhuang_wheat_guide.txt 属于既有样例，不动。
DOC_PAIRS=(
    "luyi_wheat_pest_control_2026.txt|DISEASE_PEST"
    "luyi_subsidy_policy_2026.txt|POLICY"
)

# docker compose 按【服务名】寻址（非 container_name）
AI_SERVICE="ai-service"
POSTGRES_SVC="postgres"

if [ -t 1 ]; then
    GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; NC=$'\033[0m'
else
    GREEN=''; RED=''; YELLOW=''; NC=''; BOLD=''
fi

say() { printf '%s\n' "$*"; }
ok()  { printf '%s[ OK ]%s %s\n' "${GREEN}" "${NC}" "$*"; }
warn(){ printf '%s[WARN]%s %s\n' "${YELLOW}" "${NC}" "$*"; }
die() { printf '%s[FAIL]%s %s\n' "${RED}" "${NC}" "$*" >&2; exit 1; }

echo "${BOLD}==> 智汇于庄 · RAG 文档向量化入库（租户 ${TENANT_ID}）${NC}"

# ---------- 0) 前置检查：容器在跑 ----------
if ! command -v docker >/dev/null 2>&1; then
    die "未检测到 docker，请先安装并启动 Docker Desktop。"
fi
if [ -z "$("${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" ps -q "${AI_SERVICE}" 2>/dev/null)" ]; then
    die "ai-service 服务未运行。请先执行：make all-up（或 docker compose -f ${COMPOSE_FILE} up -d ai-service postgres）"
fi
if [ -z "$("${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" ps -q "${POSTGRES_SVC}" 2>/dev/null)" ]; then
    die "postgres 服务未运行。请先执行：make all-up"
fi

# ---------- 1) 旧镜像兼容：容器内补齐 scripts/ 与 data/raw_docs ----------
# 镜像重建后（已 COPY scripts + data）以下探测均命中，直接跳过，无需 cp。
if ! "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${AI_SERVICE}" \
        sh -lc 'test -f /app/scripts/ingest_docs.py' >/dev/null 2>&1; then
    warn "容器内未发现 /app/scripts/ingest_docs.py（旧镜像）→ compose cp 补入后继续"
    # 旧镜像可能连 /app/scripts 目录都不存在，docker cp 到不存在的父目录会报
    # "Could not find the file /app/scripts"，需先建目录再 cp。
    "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${AI_SERVICE}" \
        sh -lc 'mkdir -p /app/scripts'
    "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" cp ai-service/scripts/ingest_docs.py \
        "${AI_SERVICE}":/app/scripts/ingest_docs.py
fi

need_cp=0
for pair in "${DOC_PAIRS[@]}"; do
    file="${pair%%|*}"
    if ! "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${AI_SERVICE}" \
            sh -lc "test -f /app/data/raw_docs/${file}" >/dev/null 2>&1; then
        need_cp=1
        break
    fi
done
if [ "${need_cp}" = "1" ]; then
    warn "容器内 raw_docs 缺文件（旧镜像）→ compose cp 补入两份属地化文档"
    "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${AI_SERVICE}" sh -lc 'mkdir -p /app/data/raw_docs'
    for pair in "${DOC_PAIRS[@]}"; do
        file="${pair%%|*}"
        "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" cp "ai-service/data/raw_docs/${file}" \
            "${AI_SERVICE}":/app/data/raw_docs/${file}
    done
fi

# ---------- 2) 幂等清理旧切片（该租户 + 历史 global 遗留），避免重复/混模 ----------
echo
echo "${BOLD}清理旧切片（doc_title 级幂等）...${NC}"
DOC_TITLES="('luyi_wheat_pest_control_2026', 'luyi_subsidy_policy_2026')"
CLEAN_SQL="DO \$\$
BEGIN
    IF to_regclass('public.t_knowledge_chunk') IS NOT NULL THEN
        DELETE FROM t_knowledge_chunk
         WHERE doc_title IN ${DOC_TITLES}
           AND tenant_id IN ('${TENANT_ID}', 'global');
    END IF;
END \$\$;"
"${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${POSTGRES_SVC}" \
    psql -U "${PG_USER}" -d "${PG_DB}" -v ON_ERROR_STOP=1 -c "${CLEAN_SQL}" >/dev/null
ok "旧切片清理完成（表不存在时自动跳过）"

# ---------- 3) 逐 (文件, 分类) 分组入库 ----------
for pair in "${DOC_PAIRS[@]}"; do
    file="${pair%%|*}"
    category="${pair##*|}"
    slug="${file%.txt}"
    stage="/tmp/rag_seed_${slug}"

    echo
    echo "${BOLD}[ingest] ${file} -> category=${category}${NC}"
    # 容器内把该单文件 stage 到独立目录（避免 discover 混入其它文档）
    "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${AI_SERVICE}" \
        sh -lc "rm -rf ${stage} && mkdir -p ${stage} && cp /app/data/raw_docs/${file} ${stage}/"
    # 从 ai-service 容器内以当前运行期嵌入模式切片入库（--init-table 幂等建表）
    "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${AI_SERVICE}" \
        python scripts/ingest_docs.py \
        --input-dir "${stage}" \
        --tenant-id "${TENANT_ID}" \
        --category "${category}" \
        --init-table \
        --verbose
    # 清理 stage 临时目录
    "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${AI_SERVICE}" \
        sh -lc "rm -rf ${stage}"
    ok "已入库 ${file}"
done

# ---------- 4) 落库自检 ----------
echo
echo "${BOLD}落库自检（t_knowledge_chunk）...${NC}"
CHECK_SQL="SELECT tenant_id, doc_title, category, count(*) AS chunks
             FROM t_knowledge_chunk
            WHERE tenant_id = '${TENANT_ID}'
              AND doc_title IN ${DOC_TITLES}
            GROUP BY tenant_id, doc_title, category
            ORDER BY doc_title;"
"${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" exec -T "${POSTGRES_SVC}" \
    psql -U "${PG_USER}" -d "${PG_DB}" -c "${CHECK_SQL}"

echo
echo "${GREEN}==> RAG 文档向量化入库完成（租户 ${TENANT_ID}）${NC}"
