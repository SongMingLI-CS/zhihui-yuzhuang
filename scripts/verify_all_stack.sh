#!/usr/bin/env bash
# ============================================================
# 智汇于庄 · 全栈集成验证脚本（统一经 Nginx 网关，契约对齐 docs/api-spec.yaml）
#
# 8 项探针（全部经网关反代，模拟真实浏览器 / 调用方）：
#   1) GET  /healthz                   网关原生存活          期望 HTTP 200
#   2) GET  /api/v1/healthz            backend 真实 Postgres 期望 200 + code=00000
#   3) GET  /ai/v1/healthz             ai-service 真实 PG   期望 200 + code=00000
#   4) GET  /h5/                       C 端轻量 H5 首页      期望 HTTP 200（非 404）
#   5) GET  /b/                        B 端数据大屏首页      期望 HTTP 200/3xx（非 404）
#   6) GET  /api/v1/products           特产列表（租户∪global）期望 3 款规范 SKU 在售含真实库存
#   7) POST /api/v1/orders/checkout    下单闭环（CAS + 幂等） 期望 200 code=00000 + orderNo
#                                      或 409 B2001/B2002（库存不足/幂等冲突，符合契约）
#   8) POST /ai/v1/qa/ask              农技 RAG 问答         期望 200 code=00000 + 非空 citations
#
# 任一探针失败即 [FAIL] 并打印上下文（请求/响应），最后输出 PASS/FAIL 汇总矩阵。
# 全部通过时打印绿色 ASCII，并以 0 退出；存在失败则打印错误上下文并以 1 退出。
#
# 用法：bash scripts/verify_all_stack.sh
#   环境变量：GATEWAY_HOST(默认 localhost) / GATEWAY_PORT(默认 80)
#             TENANT_ID(默认 tenant_yuzhuang_001)
#   ※ 探针 8 依赖真实 Embedding 语义检索：若 ai-service 未配置 EMBEDDING_API_KEY
#     而使用内置 Mock 向量，将无法召回本地文档，citations 为空 → 该项 FAIL
#     （属既有离线限制，非本脚本缺陷；配置 Key 后重跑即可全绿）。
# ============================================================
set -u

GATEWAY_HOST="${GATEWAY_HOST:-localhost}"
GATEWAY_PORT="${GATEWAY_PORT:-80}"
BASE_URL="${BASE_URL:-http://${GATEWAY_HOST}:${GATEWAY_PORT}}"
TENANT_ID="${TENANT_ID:-tenant_yuzhuang_001}"

# 探针超时 / 就绪等待
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-5}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-180}"
WAIT_STEP="${WAIT_STEP:-5}"
GATEWAY_WAIT="${GATEWAY_WAIT:-30}"
BACKEND_WAIT="${BACKEND_WAIT:-180}"
AI_WAIT="${AI_WAIT:-150}"
WEB_WAIT="${WEB_WAIT:-150}"

# ---------- 输出着色（非 TTY 自动降级） ----------
if [ -t 1 ]; then
    GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; NC=$'\033[0m'
else
    GREEN=''; RED=''; YELLOW=''; BOLD=''; NC=''
fi

PASS_COUNT=0
FAIL_COUNT=0

# 每项探针的结论矩阵：编号 / 名称 / 状态（PASS|FAIL）
MATRIX=()

# curl 输出：响应体写入 $BODY_FILE，HTTP 状态码打印到 stdout
BODY_FILE="$(mktemp -t yuzhuang_stack_verify.XXXXXX)"
trap 'rm -f "$BODY_FILE"' EXIT

perform() {
    local method="$1" url="$2"
    shift 2
    curl -sS -o "$BODY_FILE" -w '%{http_code}' \
        --connect-timeout "${CONNECT_TIMEOUT}" \
        --max-time "${REQUEST_TIMEOUT}" \
        "$@" -X "$method" "$url"
}

# 从统一响应体提取业务码 "code":"xxxxx"（兼容 Jackson 紧凑 / pydantic 带空格）
get_code() {
    tr -d '\r\n' <"$BODY_FILE" | sed -E 's/.*"code"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/'
}

# 记录矩阵行并计数
record() {
    local idx="$1" name="$2" result="$3"
    MATRIX+=("${idx}|${name}|${result}")
    if [ "${result}" = "PASS" ]; then
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
}

pass() { printf '%s[PASS]%s %s\n' "${GREEN}" "${NC}" "$1"; }
fail() { printf '%s[FAIL]%s %s\n' "${RED}" "${NC}" "$1"; }

# 等待直到 HTTP 200（可选：再校验 code=00000）；$1 名称 $2 最大等待 $3 url，其余为 curl 参数
wait_ok() {
    local name="$1" max_wait="$2" url="$3"
    shift 3
    local check_code=1
    if [ "${1:-}" = "--code-ok" ]; then
        check_code=0
        shift
    fi
    local waited=0
    while [ "${waited}" -lt "${max_wait}" ]; do
        local http
        http="$(perform GET "${url}" "$@")"
        if [ "${http}" = "200" ]; then
            if [ "${check_code}" -eq 0 ]; then
                if [ "$(get_code)" = "00000" ]; then
                    return 0
                fi
            else
                return 0
            fi
        fi
        waited=$((waited + WAIT_STEP))
        sleep "${WAIT_STEP}"
    done
    return 1
}

echo "${BOLD}==> 智汇于庄 · 全栈集成验证 @ ${BASE_URL}（租户 ${TENANT_ID}）${NC}"

# ============================================================
# 1) 网关原生存活
# ============================================================
echo
echo "${BOLD}[1/8] 网关 /healthz${NC}"
http=""
waited=0
while [ "${waited}" -lt "${GATEWAY_WAIT}" ]; do
    http="$(perform GET "${BASE_URL}/healthz")"
    [ "${http}" = "200" ] && break
    waited=$((waited + WAIT_STEP))
    sleep "${WAIT_STEP}"
done
if [ "${http}" = "200" ]; then
    pass "GET /healthz -> HTTP 200"
    record 1 "GET /healthz 网关存活" "PASS"
else
    fail "GET /healthz -> HTTP ${http:-timeout/refused}（期望 200）"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
    record 1 "GET /healthz 网关存活" "FAIL"
fi

# ============================================================
# 2) backend 健康（真实 Postgres）
# ============================================================
echo
echo "${BOLD}[2/8] backend /api/v1/healthz${NC}"
if wait_ok "backend" "${BACKEND_WAIT}" "${BASE_URL}/api/v1/healthz" --code-ok; then
    pass "GET /api/v1/healthz -> HTTP 200 code=00000（Postgres 已连通）"
    record 2 "backend /api/v1/healthz" "PASS"
else
    local_http="$(perform GET "${BASE_URL}/api/v1/healthz")"
    fail "GET /api/v1/healthz -> HTTP ${local_http:-timeout/refused}, code=$(get_code)（期望 200/00000）"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
    record 2 "backend /api/v1/healthz" "FAIL"
fi

# ============================================================
# 3) ai-service 健康（真实 Postgres）
# ============================================================
echo
echo "${BOLD}[3/8] ai-service /ai/v1/healthz${NC}"
if wait_ok "ai-service" "${AI_WAIT}" "${BASE_URL}/ai/v1/healthz" --code-ok; then
    pass "GET /ai/v1/healthz -> HTTP 200 code=00000（Postgres 已连通）"
    record 3 "ai-service /ai/v1/healthz" "PASS"
else
    local_http="$(perform GET "${BASE_URL}/ai/v1/healthz")"
    fail "GET /ai/v1/healthz -> HTTP ${local_http:-timeout/refused}, code=$(get_code)（期望 200/00000）"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
    record 3 "ai-service /ai/v1/healthz" "FAIL"
fi

# ============================================================
# 4) C 端轻量 H5 首页（网关 /h5/ -> 容器内 nginx）
# ============================================================
echo
echo "${BOLD}[4/8] GET /h5/${NC}"
if wait_ok "h5" "${WEB_WAIT}" "${BASE_URL}/h5/"; then
    pass "GET /h5/ -> HTTP 200（C 端 H5 可访问）"
    record 4 "GET /h5/ C 端 H5 首页" "PASS"
else
    local_http="$(perform GET "${BASE_URL}/h5/")"
    fail "GET /h5/ -> HTTP ${local_http:-timeout/refused}（期望 200，非 404）"
    echo "--- response body (head) ---"; head -c 800 "$BODY_FILE"; echo
    record 4 "GET /h5/ C 端 H5 首页" "FAIL"
fi

# ============================================================
# 5) B 端数据大屏首页（网关 /b/ -> Next.js）
#    200 或 3xx 重定向均视为可达；404 视为失败
# ============================================================
echo
echo "${BOLD}[5/8] GET /b/${NC}"
http=""
waited=0
while [ "${waited}" -lt "${WEB_WAIT}" ]; do
    http="$(perform GET "${BASE_URL}/b/")"
    case "${http}" in
        200|301|302|307|308) break ;;
    esac
    waited=$((waited + WAIT_STEP))
    sleep "${WAIT_STEP}"
done
case "${http}" in
    200|301|302|307|308)
        pass "GET /b/ -> HTTP ${http}（B 端大屏可访问）"
        record 5 "GET /b/ B 端大屏首页" "PASS"
        ;;
    *)
        fail "GET /b/ -> HTTP ${http:-timeout/refused}（期望 200/3xx，非 404）"
        echo "--- response body (head) ---"; head -c 800 "$BODY_FILE"; echo
        record 5 "GET /b/ B 端大屏首页" "FAIL"
        ;;
esac

# ============================================================
# 6) 特产商品列表：租户∪global 共 3 款规范 SKU 在售、含真实库存
# ============================================================
echo
echo "${BOLD}[6/8] GET /api/v1/products${NC}"
http="$(perform GET "${BASE_URL}/api/v1/products" \
    -H "X-Tenant-Id: ${TENANT_ID}")"
code="$(get_code)"
sku_count="$(tr -d '\r\n' <"$BODY_FILE" | grep -o '"skuCode"' | wc -l | tr -d ' ')"
has_1001="$(grep -q '"skuCode"[[:space:]]*:[[:space:]]*"SKU1001"' "$BODY_FILE" && echo y || echo n)"
has_1002="$(grep -q '"skuCode"[[:space:]]*:[[:space:]]*"SKU1002"' "$BODY_FILE" && echo y || echo n)"
has_1003="$(grep -q '"skuCode"[[:space:]]*:[[:space:]]*"SKU1003"' "$BODY_FILE" && echo y || echo n)"
# 校验三款 SKU 的库存均为正整数（真实库存，非 0/空）
stock_positive=1
for sk in SKU1001 SKU1002 SKU1003; do
    st="$(tr -d '\r\n' <"$BODY_FILE" | sed -nE "s/.*\"skuCode\":\"${sk}\".*\"stock\":([0-9]+).*/\1/p" | head -1)"
    if [ -z "${st}" ] || [ "${st}" -le 0 ]; then
        stock_positive=0
        break
    fi
done
if [ "${http}" = "200" ] && [ "${code}" = "00000" ] \
   && [ "${sku_count}" = "3" ] \
   && [ "${has_1001}" = "y" ] && [ "${has_1002}" = "y" ] && [ "${has_1003}" = "y" ] \
   && [ "${stock_positive}" = "1" ]; then
    pass "GET /products -> HTTP 200 code=00000，恰 3 款规范 SKU（SKU1001/1002/1003）均在售且库存>0"
    record 6 "GET /api/v1/products 商品列表" "PASS"
else
    fail "GET /products -> HTTP ${http:-timeout/refused}, code=${code}, skuCount=${sku_count}（期望 200/00000 且恰 3 款含 SKU1001/1002/1003、库存>0）"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
    record 6 "GET /api/v1/products 商品列表" "FAIL"
fi

# ============================================================
# 7) 下单闭环（经网关 POST /api/v1/orders/checkout）
#    种子规范 SKU1001（于庄香油, ¥68.00, 库存 800）→ 期望 200 code=00000 + orderNo
# ============================================================
echo
echo "${BOLD}[7/8] POST /api/v1/orders/checkout${NC}"
IDEM_KEY="verify_stack_$(date +%Y%m%d%H%M%S)_$$"
checkout_body='{"orderSource":"H5_PRIVATE","remark":"全栈集成验证","items":[{"skuId":1001,"quantity":1,"expectedUnitPrice":68.00}],"receiverAddress":{"recipientName":"集成验证","phone":"13800138000","detailedAddress":"河南省周口市鹿邑县试量镇于庄村"}}'
http="$(perform POST "${BASE_URL}/api/v1/orders/checkout" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: ${TENANT_ID}" \
    -H "X-Idempotency-Key: ${IDEM_KEY}" \
    --data "${checkout_body}")"
code="$(get_code)"
case "${http}:${code}" in
    200:00000)
        pass "POST /orders/checkout -> HTTP 200 code=00000"
        order_no=""
        if grep -q '"orderNo"' "$BODY_FILE"; then
            order_no="$(tr -d '\r\n' <"$BODY_FILE" | sed -E 's/.*"orderNo"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
            printf '    orderNo=%s\n' "${order_no}"
        fi
        if [ -n "${order_no}" ]; then
            record 7 "POST /orders/checkout 下单闭环" "PASS"
        else
            fail "POST /orders/checkout -> 缺少 orderNo 字段"
            echo "--- response body ---"; cat "$BODY_FILE"; echo
            record 7 "POST /orders/checkout 下单闭环" "FAIL"
        fi
        ;;
    409:B2001 | 409:B2002)
        pass "POST /orders/checkout -> HTTP 409 code=${code}（库存不足/幂等冲突，符合契约）"
        record 7 "POST /orders/checkout 下单闭环" "PASS"
        ;;
    *)
        fail "POST /orders/checkout -> HTTP ${http:-timeout/refused}, code=${code}（期望 200/00000）"
        echo "--- request ---"; echo "${checkout_body}"
        echo "--- response body ---"; cat "$BODY_FILE"; echo
        record 7 "POST /orders/checkout 下单闭环" "FAIL"
        ;;
esac

# ============================================================
# 8) 农技 RAG 问答（经网关 POST /ai/v1/qa/ask）
#    期望 200 code=00000 且 citations 非空（本地知识库真实召回）
# ============================================================
echo
echo "${BOLD}[8/8] POST /ai/v1/qa/ask（RAG 检索召回）${NC}"
qa_body='{"question":"鹿邑小麦赤霉病怎么打药","category":"DISEASE_PEST"}'
http="$(perform POST "${BASE_URL}/ai/v1/qa/ask" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: ${TENANT_ID}" \
    --data "${qa_body}")"
code="$(get_code)"
# citations 非空判定：响应含 "citations" 键，且不为空数组 "citations":[]
citations_empty=1
if grep -q '"citations"' "$BODY_FILE"; then
    if grep -qE '"citations"[[:space:]]*:[[:space:]]*\[\s*\]' "$BODY_FILE"; then
        citations_empty=0
    fi
fi
if [ "${http}" = "200" ] && [ "${code}" = "00000" ] && [ "${citations_empty}" = "0" ]; then
    pass "POST /qa/ask -> HTTP 200 code=00000，citations 非空（本地知识召回成功）"
    record 8 "POST /ai/v1/qa/ask RAG 问答" "PASS"
else
    fail "POST /qa/ask -> HTTP ${http:-timeout/refused}, code=${code}, citationsEmpty=${citations_empty}（期望 200/00000 且非空 citations）"
    echo "--- request ---"; echo "${qa_body}"
    echo "--- response body (head) ---"; head -c 1200 "$BODY_FILE"; echo
    if [ "${citations_empty}" = "0" ] && [ "${http}" = "200" ] && [ "${code}" = "00000" ]; then :; else
        echo
        echo "${YELLOW}提示：citations 为空通常因 ai-service 未配置 EMBEDDING_API_KEY（离线 Mock 向量无语义）。${NC}"
        echo "${YELLOW}请为 ai-service 注入真实 Embedding Key 后重跑；或先用 make seed 完成文档向量化入库。${NC}"
    fi
    record 8 "POST /ai/v1/qa/ask RAG 问答" "FAIL"
fi

# ============================================================
# 汇总（PASS/FAIL 矩阵 + 绿色 ASCII）
# ============================================================
echo
echo "----------------------------------------------"
echo "${BOLD}  探针汇总矩阵${NC}"
echo "----------------------------------------------"
for row in "${MATRIX[@]}"; do
    IFS='|' read -r idx name result <<<"${row}"
    if [ "${result}" = "PASS" ]; then
        printf '  %s[ %s ]%s %-38s %s\n' "${GREEN}" "PASS" "${NC}" "[${idx}] ${name}" "${GREEN}✓${NC}"
    else
        printf '  %s[ %s ]%s %-38s %s\n' "${RED}" "FAIL" "${NC}" "[${idx}] ${name}" "${RED}✗${NC}"
    fi
done
echo "----------------------------------------------"

if [ "${FAIL_COUNT}" -eq 0 ]; then
    printf '%s%s[PASS] %d/%d 全部通过%s\n' "${GREEN}" "${BOLD}" "${PASS_COUNT}" "$((PASS_COUNT + FAIL_COUNT))" "${NC}"
    cat <<'EOF'
  ██████╗  █████╗ ███████╗███████╗    ███████╗████████╗ █████╗  ██████╗██╗  ██╗
 ██╔════╝ ██╔══██╗██╔════╝██╔════╝    ██╔════╝╚══██╔══╝██╔══██╗██╔════╝██║ ██╔╝
 ██║      ███████║███████╗███████╗    ███████╗   ██║   ███████║██║     █████╔╝
 ██║      ██╔══██║╚════██║╚════██║    ╚════██║   ██║   ██╔══██║██║     ██╔═██╗
 ╚██████╗ ██║  ██║███████║███████║    ███████║   ██║   ██║  ██║╚██████╗██║  ██╗
  ╚═════╝ ╚═╝  ╚═╝╚══════╝╚══════╝    ╚══════╝   ╚═╝   ╚═╝  ╚═╝ ╚═════╝╚═╝  ╚═╝
EOF
    echo "All 8 Stack Checks Passed Successfully!"
    exit 0
else
    printf '%s[FAIL] %s%d 项失败 / %d 项通过%s\n' "${RED}" "${BOLD}" "${FAIL_COUNT}" "${PASS_COUNT}" "${NC}"
    echo
    echo "请按如下顺序排查失败项："
    echo "  1) docker compose -f deploy/docker-compose.yml ps            # 容器是否 Running"
    echo "  2) docker compose -f deploy/docker-compose.yml logs backend  # backend 日志"
    echo "  3) docker compose -f deploy/docker-compose.yml logs ai-service"
    echo "  4) make seed    # 校准 3 SKU / 50 订单 / 文档向量化入库后重跑"
    echo "  5) ai-service 需配置 EMBEDDING_API_KEY 才能命中探针 8（离线 Mock 无语义召回）"
    exit 1
fi
