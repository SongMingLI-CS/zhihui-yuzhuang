#!/usr/bin/env bash
# ============================================================
# 智汇于庄 · 网关端到端验证脚本
#
# 校验链路（统一经 Nginx 网关反向代理，契约对齐 docs/api-spec.yaml）：
#   1) GET  /healthz                    网关原生存活       期望 HTTP 200
#   2) GET  /api/v1/healthz             backend  真实 Postgres SELECT 1
#                                      （Spring Boot）  期望 200 + code=00000
#   3) GET  /ai/v1/healthz              ai-service 真实 Postgres ping
#                                      （FastAPI）     期望 200 + code=00000
#   4) POST /api/v1/orders/checkout     下单闭环（CAS 防超卖 + 幂等）
#                                      期望 200 code=00000（或 409 B2001/B2002）
#   5) POST /ai/v1/marketing/generate   多 Agent 营销生成
#                                      期望 200 code=00000
#
# 任一探针失败即 [FAIL] 并打印完整响应，最终以非零码退出。
# 用法：bash scripts/verify_gateway.sh
#   环境变量：GATEWAY_HOST(默认 localhost) / GATEWAY_PORT(默认 80)
# ============================================================
set -u

GATEWAY_HOST="${GATEWAY_HOST:-localhost}"
GATEWAY_PORT="${GATEWAY_PORT:-80}"
BASE_URL="${BASE_URL:-http://${GATEWAY_HOST}:${GATEWAY_PORT}}"

# 探针超时 / 就绪等待
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-5}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-180}"
WAIT_STEP="${WAIT_STEP:-5}"
GATEWAY_WAIT="${GATEWAY_WAIT:-30}"
BACKEND_WAIT="${BACKEND_WAIT:-150}"
AI_WAIT="${AI_WAIT:-120}"

# ---------- 输出着色（非 TTY 自动降级） ----------
if [ -t 1 ]; then
    GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; NC=$'\033[0m'
else
    GREEN=''; RED=''; YELLOW=''; BOLD=''; NC=''
fi

PASS_COUNT=0
FAIL_COUNT=0

# curl 输出：响应体写入 $BODY_FILE，HTTP 状态码打印到 stdout
BODY_FILE="$(mktemp -t yuzhuang_verify.XXXXXX)"
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

# 等待直到谓词函数（环境变量 PREDICATE_OK=1 由调用方设置）满足；返回 0 成功
wait_until() {
    local name="$1" max_wait="$2" url="$3"
    shift 3
    local waited=0 ok=1
    while [ "${waited}" -lt "${max_wait}" ]; do
        local http
        http="$(perform GET "${url}" "$@")"
        local code
        code="$(get_code)"
        if [ "${http}" = "200" ] && [ "${code}" = "00000" ]; then
            ok=0
            break
        fi
        waited=$((waited + WAIT_STEP))
        sleep "${WAIT_STEP}"
    done
    return ${ok}
}

pass() { PASS_COUNT=$((PASS_COUNT + 1)); printf '%s[PASS]%s %s\n' "${GREEN}" "${NC}" "$1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); printf '%s[FAIL]%s %s\n' "${RED}" "${NC}" "$1"; }

echo "${BOLD}==> 智汇于庄 · 网关端到端验证 @ ${BASE_URL}${NC}"

# ============================================================
# 1) 网关原生存活
# ============================================================
echo
echo "${BOLD}[1/5] 网关 /healthz${NC}"
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
else
    fail "GET /healthz -> HTTP ${http:-timeout/refused}（期望 200）"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
fi

# ============================================================
# 2) backend 健康（真实 Postgres）
# ============================================================
echo
echo "${BOLD}[2/5] backend /api/v1/healthz${NC}"
if wait_until "backend" "${BACKEND_WAIT}" "${BASE_URL}/api/v1/healthz"; then
    pass "GET /api/v1/healthz -> HTTP 200 code=00000（Postgres 已连通）"
else
    local_http="$(perform GET "${BASE_URL}/api/v1/healthz")"
    fail "GET /api/v1/healthz -> HTTP ${local_http:-timeout/refused}, code=$(get_code)（期望 200/00000）"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
fi

# ============================================================
# 3) ai-service 健康（真实 Postgres）
# ============================================================
echo
echo "${BOLD}[3/5] ai-service /ai/v1/healthz${NC}"
if wait_until "ai-service" "${AI_WAIT}" "${BASE_URL}/ai/v1/healthz"; then
    pass "GET /ai/v1/healthz -> HTTP 200 code=00000（Postgres 已连通）"
else
    local_http="$(perform GET "${BASE_URL}/ai/v1/healthz")"
    fail "GET /ai/v1/healthz -> HTTP ${local_http:-timeout/refused}, code=$(get_code)（期望 200/00000）"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
fi

# ============================================================
# 4) 下单闭环（CAS 防超卖 + 幂等，经网关 POST /api/v1/orders/checkout）
#    种子规范 SKU id=1001（SKU1001 于庄石磨香油, ¥68.00, 库存 800）
#    → 正常应 200 code=00000（或 409 B2001/B2002 库存不足/幂等冲突）
# ============================================================
echo
echo "${BOLD}[4/5] POST /api/v1/orders/checkout${NC}"
IDEM_KEY="verify_gateway_$(date +%Y%m%d%H%M%S)_$$"
checkout_body='{"orderSource":"H5_PRIVATE","remark":"网关端到端验证","items":[{"skuId":1001,"quantity":1,"expectedUnitPrice":68.00}],"receiverAddress":{"recipientName":"网关验证","phone":"13800138000","detailedAddress":"河南省周口市鹿邑县试量镇于庄村"}}'
http="$(perform POST "${BASE_URL}/api/v1/orders/checkout" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: tenant_yuzhuang_001" \
    -H "X-Idempotency-Key: ${IDEM_KEY}" \
    --data "${checkout_body}")"
code="$(get_code)"
case "${http}:${code}" in
    200:00000)
        pass "POST /orders/checkout -> HTTP 200 code=00000"
        if grep -q '"orderNo"' "$BODY_FILE"; then
            order_no="$(tr -d '\r\n' <"$BODY_FILE" | sed -E 's/.*"orderNo"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
            printf '    orderNo=%s\n' "${order_no}"
        fi
        ;;
    409:B2001 | 409:B2002)
        pass "POST /orders/checkout -> HTTP 409 code=${code}（库存不足/幂等冲突，符合契约）"
        ;;
    *)
        fail "POST /orders/checkout -> HTTP ${http:-timeout/refused}, code=${code}（期望 200/00000）"
        echo "--- request ---"; echo "${checkout_body}"
        echo "--- response body ---"; cat "$BODY_FILE"; echo
        ;;
esac

# ============================================================
# 5) AI 多 Agent 营销生成（经网关 POST /ai/v1/marketing/generate）
# ============================================================
echo
echo "${BOLD}[5/5] POST /ai/v1/marketing/generate${NC}"
marketing_body='{"product_name":"于庄传统石磨小磨香油","selling_points":["古法初榨","无任何添加剂","芝麻原香浓郁"],"target_audience":"注重食品健康的家庭主妇","channel_preferences":["MOMENTS","RED_BOOK"]}'
http="$(perform POST "${BASE_URL}/ai/v1/marketing/generate" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: tenant_yuzhuang_001" \
    --data "${marketing_body}")"
code="$(get_code)"
if [ "${http}" = "200" ] && [ "${code}" = "00000" ]; then
    pass "POST /marketing/generate -> HTTP 200 code=00000"
else
    fail "POST /marketing/generate -> HTTP ${http:-timeout/refused}, code=${code}（期望 200/00000）"
    echo "--- request ---"; echo "${marketing_body}"
    echo "--- response body ---"; cat "$BODY_FILE"; echo
fi

# ============================================================
# 汇总
# ============================================================
echo
echo "----------------------------------------------"
if [ "${FAIL_COUNT}" -eq 0 ]; then
    printf '%s%s[PASS] %d/%d 全部通过%s\n' "${GREEN}" "${BOLD}" "${PASS_COUNT}" "$((PASS_COUNT + FAIL_COUNT))" "${NC}"
    echo "All Gateway Endpoints Verified Successfully!"
    exit 0
else
    printf '%s[FAIL] %s%d 项失败 / %d 项通过%s\n' "${RED}" "${BOLD}" "${FAIL_COUNT}" "${PASS_COUNT}" "${NC}"
    echo "Verification failed. 请检查上述失败探针的响应与容器日志（docker compose -f deploy/docker-compose.yml logs）。"
    exit 1
fi
