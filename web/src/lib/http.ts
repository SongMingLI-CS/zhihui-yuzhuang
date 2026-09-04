import axios, { type AxiosResponse } from 'axios';
import { AI_BASE, API_BASE, AI_QA_TIMEOUT, AI_MARKETING_TIMEOUT, GATEWAY_BASE } from './config';
import { getTenant } from './tenant';
import type {
  AgriQARequest,
  AgriQAResponse,
  ApiResponse,
  MarketingGenerateRequest,
  MarketingGenerateResponse,
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

http.interceptors.request.use((config) => {
  config.headers.set('X-Tenant-Id', getTenant().id);
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

/* ===================== 农技问答（POST /ai/v1/qa/ask，防幻觉溯源） ===================== */

export async function askAgri(payload: AgriQARequest): Promise<AgriQAResponse> {
  const res = await http.post<ApiResponse<AgriQAResponse>>(`${AI_BASE}/qa/ask`, payload, {
    timeout: AI_QA_TIMEOUT,
  });
  return unwrap(res);
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
