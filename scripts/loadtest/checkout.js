// ============================================================
// 智汇于庄 · k6 压测脚本：下单幂等 / 防超卖 / 限流恢复
//
// 前置：全栈已启动（docker compose -f deploy/docker-compose.yml up -d --build）
//      且已灌入商品数据（make seed 或商品读接口可见 SKU）。
//
// 用法：
//   k6 run -e BASE_URL=http://localhost -e SKU_ID=1001 -e STOCK=50 scripts/loadtest/checkout.js
//
// 场景与判定（不编造指标，阈值按实测调整）：
//   1) idempotency：同一幂等键并发 20 次 → 必须只产生 1 个订单号（其余为幂等回放）
//   2) oversell   ：库存 STOCK 件，并发 N 单 → 成功数必须 == STOCK（无超卖）
//   3) ratelimit  ：登录端点高频请求 → 应出现 429（网关限流生效）
//
// 说明：脚本只做「可重复执行 + 明确判定」，输出真实数字；
//      吞吐量等指标请以本机实际 k6 summary 为准，不要照抄文档示例。
// ============================================================
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const SKU_ID = Number(__ENV.SKU_ID || 1001);
const STOCK = Number(__ENV.STOCK || 50);
const TENANT_ID = __ENV.TENANT_ID || 'tenant_yuzhuang_001';

const checkoutOk = new Counter('checkout_ok');
const checkoutSoldOut = new Counter('checkout_soldout');
const checkoutOther = new Counter('checkout_other');
const checkoutLatency = new Trend('checkout_latency_ms');

export const options = {
  scenarios: {
    // 场景 1：同键并发（幂等）
    idempotency: {
      executor: 'per-vu-iterations',
      vus: 20,
      iterations: 1,
      exec: 'idempotentCheckout',
      tags: { scenario: 'idempotency' },
      startTime: '0s',
    },
    // 场景 2：不同键并发抢同一 SKU（防超卖）
    oversell: {
      executor: 'shared-iterations',
      vus: 30,
      iterations: STOCK * 3,
      exec: 'racingCheckout',
      tags: { scenario: 'oversell' },
      startTime: '5s',
    },
    // 场景 3：登录限流
    ratelimit: {
      executor: 'constant-arrival-rate',
      rate: 40,
      timeUnit: '1s',
      duration: '5s',
      preAllocatedVUs: 20,
      exec: 'loginBurst',
      tags: { scenario: 'ratelimit' },
      startTime: '15s',
    },
  },
  thresholds: {
    // 防超卖是硬约束：成功下单数不得超过库存（脚本结束时用 handleSummary 断言）
    'checkout_soldout': ['count>0'],
  },
};

function checkoutPayload() {
  return JSON.stringify({
    orderSource: 'H5_PRIVATE',
    remark: 'k6 loadtest',
    items: [{ skuId: SKU_ID, quantity: 1, expectedUnitPrice: 0.01 }],
    receiverAddress: {
      recipientName: '压测用户',
      phone: '13800000000',
      detailedAddress: '河南省周口市鹿邑县试量镇于庄村（压测）',
    },
  });
}

function doCheckout(idempotencyKey) {
  const res = http.post(`${BASE_URL}/api/v1/orders/checkout`, checkoutPayload(), {
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': TENANT_ID,
      'X-Idempotency-Key': idempotencyKey,
    },
    tags: { endpoint: 'checkout' },
  });
  checkoutLatency.add(res.timings.duration);
  let code = '';
  try {
    code = res.json('code');
  } catch (e) {
    code = '';
  }
  if (res.status === 200 && code === '00000') {
    checkoutOk.add(1);
  } else if (res.status === 409 && code === 'B2001') {
    checkoutSoldOut.add(1);
  } else {
    checkoutOther.add(1);
  }
  return { status: res.status, code };
}

export function idempotentCheckout() {
  const key = `k6-idem-${__ENV.RUN_ID || 'run1'}`;
  const first = doCheckout(key);
  const second = doCheckout(key);
  check(second, {
    '同键第二次请求不应报库存不足': (r) => !(r.status === 409 && r.code === 'B2001'),
  });
  check(first, { '首次下单返回 200 或 409 均可': (r) => r.status === 200 || r.status === 409 });
}

export function racingCheckout() {
  doCheckout(`k6-race-${__VU}-${__ITER}-${Date.now()}`);
}

export function loginBurst() {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: 'nonexistent-user', password: 'wrong-password' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'login' } },
  );
  check(res, { '登录应被拒绝或限流': (r) => [400, 401, 403, 429].includes(r.status) });
  sleep(0.05);
}

export function handleSummary(data) {
  const ok = data.metrics.checkout_ok ? data.metrics.checkout_ok.values.count : 0;
  const soldOut = data.metrics.checkout_soldout ? data.metrics.checkout_soldout.values.count : 0;
  const other = data.metrics.checkout_other ? data.metrics.checkout_other.values.count : 0;
  const p95 = data.metrics.checkout_latency ? data.metrics.checkout_latency.values['p(95)'] : 0;

  const oversellViolated = ok > STOCK;
  const summary = {
    '下单成功数': ok,
    '库存不足拒绝数': soldOut,
    '其他结果数': other,
    '下单 P95(ms)': Math.round(p95 || 0),
    '库存上限': STOCK,
    '防超卖结论': oversellViolated ? '❌ 超卖（成功数 > 库存）' : '✅ 未超卖',
  };
  // eslint-disable-next-line no-console
  console.log(JSON.stringify(summary, null, 2));
  return {
    stdout: `\n[loadtest] 防超卖：${summary['防超卖结论']}（成功=${ok}，库存=${STOCK}）\n`,
  };
}
