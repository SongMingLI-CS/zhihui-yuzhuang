import axios, { type AxiosResponse } from 'axios';
import { AI_BASE, API_BASE, AI_STREAMING_ENABLED, ORDER_CREDENTIALS_KEY, TENANT_ID } from '../config';
import type {
  AgriQARequest,
  AgriQAResponse,
  ApiResponse,
  GuestOrderCancelRequest,
  GuestOrderLookupRequest,
  GuestOrderLookupResponse,
  OrderCheckoutRequest,
  OrderCheckoutResponse,
  Product,
} from '../types/api';

/** 业务/网络错误统一封装（携带契约 code 与 HTTP 状态） */
export class ApiError extends Error {
  code: string;
  httpStatus: number;

  constructor(message: string, code = 'UNKNOWN', httpStatus = 0) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

const http = axios.create({ timeout: 20000 });
const ACCESS_TOKEN_KEY = 'yuzhuang.access_token';

export function setAccessToken(token: string | null): void {
  if (token) localStorage.setItem(ACCESS_TOKEN_KEY, token);
  else localStorage.removeItem(ACCESS_TOKEN_KEY);
}

function getAccessToken(): string | null {
  return typeof window === 'undefined' ? null : localStorage.getItem(ACCESS_TOKEN_KEY);
}

// 全量请求注入多租户标识（契约 Header X-Tenant-Id）
http.interceptors.request.use((config) => {
  config.headers.set('X-Tenant-Id', TENANT_ID);
  const token = getAccessToken();
  if (token) config.headers.set('Authorization', `Bearer ${token}`);
  return config;
});

/** 拆解统一包裹：code === '00000' 返回 data，否则抛业务错误 */
async function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): Promise<T> {
  const body = res.data;
  if (body && body.code === '00000') {
    return body.data as T;
  }
  throw new ApiError(body?.message || '业务处理失败', body?.code || 'UNKNOWN', res.status);
}

/** 将任意异常规范为 ApiError（优先取服务端 message） */
export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) {
    return err;
  }
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as ApiResponse<unknown> | undefined;
    const status = err.response?.status ?? 0;
    return new ApiError(
      data?.message || err.message || '网络异常，请稍后重试',
      data?.code || (status ? `HTTP_${status}` : 'NETWORK_ERROR'),
      status,
    );
  }
  return new ApiError(err instanceof Error ? err.message : '网络异常，请稍后重试');
}

/* ===================== 特产商品 ===================== */

export async function fetchProducts(): Promise<Product[]> {
  const res = await http.get<ApiResponse<Product[]>>(`${API_BASE}/products`, {
    timeout: 10000,
  });
  return unwrap(res);
}

/* ===================== 特产下单（幂等 + 多租户 Header） ===================== */

export async function checkoutOrder(
  payload: OrderCheckoutRequest,
  idempotencyKey: string,
): Promise<OrderCheckoutResponse> {
  const res = await http.post<ApiResponse<OrderCheckoutResponse>>(
    `${API_BASE}/orders/checkout`,
    payload,
    {
      // 秒杀防重复提交：由前端 crypto.randomUUID() 生成
      headers: { 'X-Idempotency-Key': idempotencyKey },
      timeout: 20000,
    },
  );
  return unwrap(res);
}

/* ===================== 本人订单查询/取消（匿名，凭证鉴权） ===================== */

/**
 * 本地保存下单返回的查询凭证（仅存订单号与凭证，不含任何个人敏感信息）。
 *
 * 凭证只用于本人订单查询/取消；服务端仅保存其 PBKDF2 哈希，无法反查。
 */
export function saveOrderCredential(orderNo: string, queryToken?: string | null): void {
  if (!orderNo || !queryToken || typeof window === 'undefined') return;
  try {
    const raw = window.localStorage.getItem(ORDER_CREDENTIALS_KEY);
    const map = raw ? (JSON.parse(raw) as Record<string, string>) : {};
    map[orderNo] = queryToken;
    // 仅保留最近 20 笔，避免本地无限增长
    const entries = Object.entries(map).slice(-20);
    window.localStorage.setItem(ORDER_CREDENTIALS_KEY, JSON.stringify(Object.fromEntries(entries)));
  } catch {
    // 本地存储不可用（隐私模式）时静默降级，用户仍可手动输入凭证
  }
}

/** 读取本地已保存的订单凭证（订单号 → 凭证，倒序）。 */
export function listOrderCredentials(): Array<{ orderNo: string; queryToken: string }> {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(ORDER_CREDENTIALS_KEY);
    if (!raw) return [];
    const map = JSON.parse(raw) as Record<string, string>;
    return Object.entries(map)
      .map(([orderNo, queryToken]) => ({ orderNo, queryToken }))
      .reverse();
  } catch {
    return [];
  }
}

/** 删除本地保存的订单凭证（如用户清理记录）。 */
export function removeOrderCredential(orderNo: string): void {
  if (typeof window === 'undefined') return;
  try {
    const raw = window.localStorage.getItem(ORDER_CREDENTIALS_KEY);
    if (!raw) return;
    const map = JSON.parse(raw) as Record<string, string>;
    delete map[orderNo];
    window.localStorage.setItem(ORDER_CREDENTIALS_KEY, JSON.stringify(map));
  } catch {
    // ignore
  }
}

/** 本人订单查询（需订单号 + 一次性查询凭证；返回脱敏状态）。 */
export async function lookupGuestOrder(
  payload: GuestOrderLookupRequest,
): Promise<GuestOrderLookupResponse> {
  const res = await http.post<ApiResponse<GuestOrderLookupResponse>>(
    `${API_BASE}/orders/guest/lookup`,
    payload,
    { timeout: 15000 },
  );
  return unwrap(res);
}

/** 本人取消未支付订单（需订单号 + 查询凭证；服务端含库存回补）。 */
export async function cancelGuestOrder(
  payload: GuestOrderCancelRequest,
): Promise<GuestOrderLookupResponse> {
  const res = await http.post<ApiResponse<GuestOrderLookupResponse>>(
    `${API_BASE}/orders/guest/cancel`,
    payload,
    { timeout: 15000 },
  );
  return unwrap(res);
}

/* ===================== 农技问答（RAG 防幻觉溯源） ===================== */

export async function askAgri(payload: AgriQARequest): Promise<AgriQAResponse> {
  const res = await http.post<ApiResponse<AgriQAResponse>>(`${AI_BASE}/qa/ask`, payload, {
    timeout: 60000,
  });
  return unwrap(res);
}

export async function askAgriStreaming(
  payload: AgriQARequest,
  callbacks: { onToken: (token: string) => void; onCitations?: (citations: AgriQAResponse['citations']) => void; signal?: AbortSignal },
): Promise<AgriQAResponse> {
  if (!AI_STREAMING_ENABLED) {
    const data = await askAgri(payload);
    callbacks.onToken(data.answer);
    callbacks.onCitations?.(data.citations);
    return data;
  }
  const token = getAccessToken();
  const response = await fetch(`${AI_BASE}/qa/ask/stream`, {
    method: 'POST', signal: callbacks.signal,
    headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': TENANT_ID, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(payload),
  });
  if (!response.ok || !response.body) throw new ApiError('流式问答连接失败', `HTTP_${response.status}`, response.status);
  const reader = response.body.getReader(); const decoder = new TextDecoder();
  let buffer = ''; let answer = ''; let citations: AgriQAResponse['citations'] = [];
  let reading = true;
  while (reading) {
    const { done, value } = await reader.read(); if (done) { reading = false; continue; }
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split('\n\n'); buffer = events.pop() ?? '';
    for (const event of events) {
      const raw = event.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n');
      if (!raw || raw === '[DONE]') continue;
      const parsed = JSON.parse(raw) as { token?: string; citations?: AgriQAResponse['citations']; message?: string };
      if (parsed.message) throw new ApiError(parsed.message, 'STREAM_ERROR');
      if (parsed.token) { answer += parsed.token; callbacks.onToken(parsed.token); }
      if (parsed.citations) { citations = parsed.citations; callbacks.onCitations?.(citations); }
    }
  }
  return { answer, citations };
}
