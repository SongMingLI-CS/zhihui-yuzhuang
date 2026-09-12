import axios, { type AxiosResponse } from 'axios';
import { AI_BASE, API_BASE, AI_QA_TIMEOUT, AI_MARKETING_TIMEOUT, AI_STREAMING_ENABLED, BASE_PATH, GATEWAY_BASE } from './config';
import { getTenant } from './tenant';
import { persistUser, setCurrentUser } from './auth';
import type {
  AgriQARequest,
  AgriQAResponse,
  ApiResponse,
  AuthLoginRequest,
  AuthLoginResponse,
  MarketingGenerateRequest,
  MarketingGenerateResponse,
  OrderListParams,
  OrderSummary,
  PageResult,
  Product,
  ProductUpsertRequest,
  DashboardSummary,
  KnowledgeDocMeta,
  KnowledgeDeleteResult,
  KnowledgeUploadResult,
  UserInfo,
  MerchantProductParams,
  GovSummary,
  AdminTenant,
  AdminUser,
  MarketingTaskPage,
  MarketingTaskItem,
  MarketingReviewStatus,
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

/** 与 h5 同源请求实例：Cookie 会话（withCredentials）+ 双提交 CSRF + 兼容 Bearer。 */
const http = axios.create({ timeout: 30_000, withCredentials: true });

/** 兼容历史 key：仅在登出时清理旧 localStorage 令牌（阶段 A 起不再写入 localStorage）。 */
const LEGACY_TOKEN_KEY = 'yuzhuang.access_token';

/** 令牌仅保存在内存（页面刷新后依赖 HttpOnly 会话 Cookie 恢复），避免 XSS 窃取长期令牌。 */
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
  if (typeof window !== 'undefined') {
    // 清理历史版本遗留的 localStorage 令牌
    window.localStorage.removeItem(LEGACY_TOKEN_KEY);
  }
}

export function getAccessToken(): string | null {
  return accessToken;
}

/** 读取 CSRF 双提交 Cookie（非 HttpOnly，服务端写入）。 */
export function readCsrfCookie(): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.split('; ').find((row) => row.startsWith('yz_csrf='));
  return match ? decodeURIComponent(match.split('=').slice(1).join('=')) : null;
}

http.interceptors.request.use((config) => {
  config.headers.set('X-Tenant-Id', getTenant().id);
  if (accessToken) config.headers.set('Authorization', `Bearer ${accessToken}`);
  const method = (config.method ?? 'get').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrf = readCsrfCookie();
    if (csrf) config.headers.set('X-CSRF-Token', csrf);
  }
  return config;
});

/**
 * 401 兜底：B 端受保护端点返回 A1002（未登录/令牌失效）时清空本地会话并回登录页。
 * 登录接口自身的 401（密码错误）与健康探针（validateStatus 全放行）不受影响。
 * 跳转路径按 basePath 拼接（修复历史忽略 `/b` 前缀的问题）。
 */
http.interceptors.response.use(undefined, (error) => {
  const status = error?.response?.status as number | undefined;
  const body = error?.response?.data as ApiResponse<unknown> | undefined;
  const url = error?.config?.url as string | undefined;
  if (
    status === 401 &&
    body?.code === 'A1002' &&
    typeof url === 'string' &&
    !url.includes('/auth/login') &&
    typeof window !== 'undefined'
  ) {
    setAccessToken(null);
    persistUser(null);
    setCurrentUser(null);
    const loginPath = `${BASE_PATH}/login`;
    if (!window.location.pathname.startsWith(loginPath)) {
      window.location.assign(loginPath);
    }
  }
  return Promise.reject(error);
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

/** 查询当前会话账号（页面刷新后凭 HttpOnly Cookie 恢复登录态）。 */
export async function fetchMe(): Promise<UserInfo> {
  const res = await http.get<ApiResponse<UserInfo>>(`${API_BASE}/auth/me`);
  return unwrap(res);
}

/** 登出：服务端清除会话 Cookie。 */
export async function logout(): Promise<void> {
  await http.post<ApiResponse<{ loggedOut: boolean }>>(`${API_BASE}/auth/logout`);
}

/** 修改密码（首次登录强制改密）。 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await http.post<ApiResponse<{ changed: boolean }>>(`${API_BASE}/auth/password/change`, {
    currentPassword,
    newPassword,
  });
}

/* ===================== 经营大盘（GET /api/v1/dashboard/summary） ===================== */

export async function fetchDashboardSummary(): Promise<DashboardSummary> {
  const res = await http.get<ApiResponse<DashboardSummary>>(`${API_BASE}/dashboard/summary`);
  return unwrap(res);
}

/* ===================== 知识库文档管理（/ai/v1/knowledge/docs） ===================== */

/** 列出本租户 ∪ global 的知识文档（ai-service 聚合元信息）。 */
export async function fetchKnowledgeDocs(): Promise<KnowledgeDocMeta[]> {
  const res = await http.get<ApiResponse<KnowledgeDocMeta[]>>(`${AI_BASE}/knowledge/docs`);
  return unwrap(res);
}

/** 上传 .txt/.md 文档：服务端切片 + 向量化 + 入库（Embedding 未配置密钥时自动 Mock）。 */
export async function uploadKnowledgeDoc(file: File): Promise<KnowledgeUploadResult> {
  const form = new FormData();
  form.append('file', file);
  form.append('tenant_id', getTenant().id);
  form.append('category', 'GENERAL');
  const res = await http.post<ApiResponse<KnowledgeUploadResult>>(
    `${AI_BASE}/knowledge/docs/upload`,
    form,
    { timeout: 120_000 },
  );
  return unwrap(res);
}

/** 删除指定租户下的知识文档（幂等）。 */
export async function deleteKnowledgeDoc(
  tenantId: string,
  title: string,
): Promise<KnowledgeDeleteResult> {
  const res = await http.post<ApiResponse<KnowledgeDeleteResult>>(
    `${AI_BASE}/knowledge/docs/delete`,
    { tenantId, title },
  );
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

/* ===================== 订单查询 / 出库（GET /orders + POST /orders/{orderNo}/ship） ===================== */

/** 分页查询本租户订单（X-Tenant-Id 由拦截器注入） */
export async function listOrders(params: OrderListParams = {}): Promise<PageResult<OrderSummary>> {
  const res = await http.get<ApiResponse<PageResult<OrderSummary>>>(`${API_BASE}/orders`, { params });
  return unwrap(res);
}

/** 订单一键出库：履约状态 READY → SHIPPED（非就绪/跨租户由后端抛契约码） */
export async function shipOrder(orderNo: string): Promise<OrderSummary> {
  const res = await http.post<ApiResponse<OrderSummary>>(`${API_BASE}/orders/${encodeURIComponent(orderNo)}/ship`);
  return unwrap(res);
}

/** 拣货完成置为待出库：PICKING → READY */
export async function markOrderReady(orderNo: string): Promise<OrderSummary> {
  const res = await http.post<ApiResponse<OrderSummary>>(`${API_BASE}/orders/${encodeURIComponent(orderNo)}/mark-ready`);
  return unwrap(res);
}

/** 异常单恢复拣货：ABNORMAL → PICKING */
export async function recoverAbnormalOrder(orderNo: string): Promise<OrderSummary> {
  const res = await http.post<ApiResponse<OrderSummary>>(`${API_BASE}/orders/${encodeURIComponent(orderNo)}/recover`);
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
    method: 'POST', signal: callbacks.signal, credentials: 'include',
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


/* ===================== 商家工作台（GET /api/v1/merchant/products） ===================== */

/** 分页查询本租户商品（含草稿/在售/下架/归档；数据域取自 JWT 主体）。 */
export async function listMerchantProducts(
  params: MerchantProductParams = {},
): Promise<PageResult<Product>> {
  const res = await http.get<ApiResponse<PageResult<Product>>>(`${API_BASE}/merchant/products`, {
    params,
  });
  return unwrap(res);
}

/* ===================== 政府治理（GET /api/v1/gov/summary） ===================== */

/** 授权范围只读聚合（含快照时间、范围与统计口径元数据）。 */
export async function fetchGovSummary(): Promise<GovSummary> {
  const res = await http.get<ApiResponse<GovSummary>>(`${API_BASE}/gov/summary`);
  return unwrap(res);
}

/* ===================== 平台管理（GET /api/v1/admin/*） ===================== */

export async function listAdminTenants(): Promise<AdminTenant[]> {
  const res = await http.get<ApiResponse<AdminTenant[]>>(`${API_BASE}/admin/tenants`);
  return unwrap(res);
}

export async function listAdminUsers(tenantId?: string): Promise<AdminUser[]> {
  const res = await http.get<ApiResponse<AdminUser[]>>(`${API_BASE}/admin/users`, {
    params: tenantId ? { tenantId } : {},
  });
  return unwrap(res);
}

/** 启用/停用账号（PLATFORM_ADMIN）。 */
export async function updateAdminUserStatus(
  userId: number,
  status: 'ACTIVE' | 'DISABLED',
  reason?: string,
): Promise<AdminUser> {
  const res = await http.patch<ApiResponse<AdminUser>>(
    `${API_BASE}/admin/users/${userId}/status`,
    { status, reason },
  );
  return unwrap(res);
}

/** 重置账号密码，返回一次性临时口令（服务端要求首次改密）。 */
export async function resetAdminUserPassword(userId: number): Promise<{
  username: string;
  temporaryPassword: string;
  mustChangePassword: boolean;
}> {
  const res = await http.post<
    ApiResponse<{ username: string; temporaryPassword: string; mustChangePassword: boolean }>
  >(`${API_BASE}/admin/users/${userId}/password/reset`);
  return unwrap(res);
}

/* ===================== 营销任务台账与审批（/ai/v1/marketing/tasks） ===================== */

/** 分页查询本租户营销任务台账（含审批状态/审批人/发布时间）。 */
export async function listMarketingTasks(
  params: { status?: MarketingReviewStatus; page?: number; pageSize?: number } = {},
): Promise<MarketingTaskPage> {
  const res = await http.get<ApiResponse<MarketingTaskPage>>(`${AI_BASE}/marketing/tasks`, {
    params: {
      status: params.status,
      page: params.page ?? 1,
      page_size: params.pageSize ?? 20,
    },
  });
  return unwrap(res);
}

/** 审批/发布动作：approve / reject / publish（严格服务端状态机）。 */
export async function marketingTaskAction(
  taskId: number,
  action: 'approve' | 'reject' | 'publish',
  comment = '',
): Promise<MarketingTaskItem> {
  const res = await http.post<ApiResponse<MarketingTaskItem>>(
    `${AI_BASE}/marketing/tasks/${taskId}/${action}`,
    { comment },
  );
  return unwrap(res);
}



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
