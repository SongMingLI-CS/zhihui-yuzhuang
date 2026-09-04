import axios, { type AxiosResponse } from 'axios';
import { AI_BASE, API_BASE, TENANT_ID } from '../config';
import type {
  AgriQARequest,
  AgriQAResponse,
  ApiResponse,
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

// 全量请求注入多租户标识（契约 Header X-Tenant-Id）
http.interceptors.request.use((config) => {
  config.headers.set('X-Tenant-Id', TENANT_ID);
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

/* ===================== 农技问答（RAG 防幻觉溯源） ===================== */

export async function askAgri(payload: AgriQARequest): Promise<AgriQAResponse> {
  const res = await http.post<ApiResponse<AgriQAResponse>>(`${AI_BASE}/qa/ask`, payload, {
    timeout: 60000,
  });
  return unwrap(res);
}
