import axios, { type AxiosResponse } from 'axios';
import { AI_BASE, API_BASE, AI_QA_TIMEOUT, AI_MARKETING_TIMEOUT, AI_STREAMING_ENABLED, GATEWAY_BASE } from './config';
import { getTenant } from './tenant';
import type {
  AgriQARequest,
  AgriQAResponse,
  ApiResponse,
  AuthLoginRequest,
  AuthLoginResponse,
  MarketingGenerateRequest,
  MarketingGenerateResponse,
  Product,
  ProductUpsertRequest,
} from './types';

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

/** 与 h5 同源请求实例：全量注入当前多租户标识 X-Tenant-Id */
const http = axios.create({ timeout: 30_000 });

const ACCESS_TOKEN_KEY = 'yuzhuang.access_token';

export function setAccessToken(token: string | null): void {
  if (typeof window === 'undefined') return;
  if (token) window.localStorage.setItem(ACCESS_TOKEN_KEY, token);
  else window.localStorage.removeItem(ACCESS_TOKEN_KEY);
}

function getAccessToken(): string | null {
  return typeof window === 'undefined' ? null : window.localStorage.getItem(ACCESS_TOKEN_KEY);
}

http.interceptors.request.use((config) => {
  config.headers.set('X-Tenant-Id', getTenant().id);
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

/* ===================== 认证（POST /api/v1/auth/login） ===================== */

export async function login(payload: AuthLoginRequest): Promise<AuthLoginResponse> {
  const res = await http.post<ApiResponse<AuthLoginResponse>>(`${API_BASE}/auth/login`, payload);
  return unwrap(res);
}

/* ===================== 商品管理（GET/POST/PUT/PATCH /api/v1/products） ===================== */

export async function listProducts(): Promise<Product[]> {
  const res = await http.get<ApiResponse<Product[]>>(`${API_BASE}/products`);
  return unwrap(res);
}

export async function createProduct(payload: ProductUpsertRequest): Promise<Product> {
  const res = await http.post<ApiResponse<Product>>(`${API_BASE}/products`, payload);
  return unwrap(res);
}

export async function updateProduct(id: number, payload: ProductUpsertRequest): Promise<Product> {
  const res = await http.put<ApiResponse<Product>>(`${API_BASE}/products/${id}`, payload);
  return unwrap(res);
}

export async function updateProductStatus(id: number, status: string): Promise<Product> {
  const res = await http.patch<ApiResponse<Product>>(`${API_BASE}/products/${id}/status`, { status });
  return unwrap(res);
}

/* ===================== 农技问答（POST /ai/v1/qa/ask，防幻觉溯源） ===================== */

export async function askAgri(payload: AgriQARequest): Promise<AgriQAResponse> {
  const res = await http.post<ApiResponse<AgriQAResponse>>(`${AI_BASE}/qa/ask`, payload, {
    timeout: AI_QA_TIMEOUT,
  });
  return unwrap(res);
}

export interface StreamCallbacks {
  onToken: (token: string) => void;
  onCitations?: (citations: AgriQAResponse['citations']) => void;
  signal?: AbortSignal;
}

/** SSE 能力适配：后端开通 /qa/ask/stream 后仅需打开环境变量；当前默认安全回退 JSON。 */
export async function askAgriStreaming(payload: AgriQARequest, callbacks: StreamCallbacks): Promise<AgriQAResponse> {
  if (!AI_STREAMING_ENABLED) {
    const data = await askAgri(payload);
    callbacks.onToken(data.answer);
    callbacks.onCitations?.(data.citations);
    return data;
  }
  const token = getAccessToken();
  const response = await fetch(`${AI_BASE}/qa/ask/stream`, {
    method: 'POST', signal: callbacks.signal,
    headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': getTenant().id, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(payload),
  });
  if (!response.ok || !response.body) throw new ApiError('流式问答连接失败', `HTTP_${response.status}`, response.status);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = ''; let answer = ''; let citations: AgriQAResponse['citations'] = [];
  let reading = true;
  while (reading) {
    const { done, value } = await reader.read();
    if (done) { reading = false; continue; }
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

/* ===================== 特产营销生成（POST /ai/v1/marketing/generate） ===================== */

export async function generateMarketing(
  payload: MarketingGenerateRequest,
): Promise<MarketingGenerateResponse> {
  const res = await http.post<ApiResponse<MarketingGenerateResponse>>(
    `${AI_BASE}/marketing/generate`,
    payload,
    { timeout: AI_MARKETING_TIMEOUT },
  );
  return unwrap(res);
}

/* ===================== 链路健康探针（顶栏链路状态指示器） ===================== */

async function probeOk(url: string, timeoutMs = 6_000): Promise<boolean> {
  try {
    const res = await http.get(url, { timeout: timeoutMs, validateStatus: () => true });
    return res.status >= 200 && res.status < 500;
  } catch {
    return false;
  }
}

/** 网关原生存活（/healthz 返回纯文本 ok） */
export function checkGateway(): Promise<boolean> {
  return probeOk(`${GATEWAY_BASE}/healthz`);
}

/** backend 健康（GET /api/v1/healthz，200 + code=00000） */
export async function checkBackend(): Promise<boolean> {
  try {
    const res = await http.get<ApiResponse<unknown>>(`${API_BASE}/healthz`, {
      timeout: 6_000,
      validateStatus: () => true,
    });
    return res.status === 200 && res.data?.code === '00000';
  } catch {
    return false;
  }
}

/** ai-service 健康（GET /ai/v1/healthz，200 + code=00000） */
export async function checkAi(): Promise<boolean> {
  try {
    const res = await http.get<ApiResponse<unknown>>(`${AI_BASE}/healthz`, {
      timeout: 6_000,
      validateStatus: () => true,
    });
    return res.status === 200 && res.data?.code === '00000';
  } catch {
    return false;
  }
}
