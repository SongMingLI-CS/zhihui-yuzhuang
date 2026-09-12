/**
 * 智汇于庄 web · 全局配置
 *
 * 网关相对路径约定（浏览器直连同源网关，无需跨域，见 deploy/nginx/nginx.conf）：
 *   - 业务后端：/api/v1/*  -> backend:8080
 *   - AI 服务：  /ai/v1/*   -> ai-service:8000
 * 本地开发可用 NEXT_PUBLIC_* 覆盖到网关地址（如 http://localhost/api/v1）。
 */
export const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? '/api/v1';
export const AI_BASE = process.env.NEXT_PUBLIC_AI_BASE ?? '/ai/v1';
export const AI_STREAMING_ENABLED = process.env.NEXT_PUBLIC_AI_STREAMING_ENABLED === 'true';
/** 网关原生存活探针（根路径 /healthz），开发环境可留空由当前源承载 */
export const GATEWAY_BASE = process.env.NEXT_PUBLIC_GATEWAY_BASE ?? '';

/**
 * 应用 basePath（与 next.config.mjs 的 basePath 保持一致）。
 * 用途：非组件环境（axios 拦截器）无法使用 next/navigation，需按 basePath 拼接登录跳转，
 * 修复历史 `window.location.assign('/login')` 忽略 `/b` 前缀导致 404 的问题。
 */
export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? '/b';

/** 角色 → 登录后落地页（政府/商家/村委/平台分区，服务端仍需强校验） */
export const ROLE_HOME: Record<string, string> = {
  PLATFORM_ADMIN: '/platform',
  GOVERNMENT: '/gov',
  VILLAGE: '/dashboard',
  COOPERATIVE: '/merchant',
  CONSUMER: '/dashboard',
};

export const resolveRoleHome = (role: string | undefined | null): string => {
  if (!role) return '/login';
  return ROLE_HOME[role] ?? '/dashboard';
};

/**
 * 是否展示“事件流水”演示卡片。
 *
 * 后端尚未提供经鉴权的 SSE/WebSocket 事件流，该卡片为**客户端模拟**；
 * 因此默认关闭（生产隐藏），需显式 `NEXT_PUBLIC_SIMULATED_REALTIME_FEED=true` 才展示，
 * 且卡片标题明确标注“演示：非生产实时”，不得被当作实时经营数据。
 */
export const SIMULATED_REALTIME_FEED =
  process.env.NEXT_PUBLIC_SIMULATED_REALTIME_FEED === 'true';

export const APP_TITLE = '智汇于庄 · 数字产业中台与治理大脑';
export const APP_SHORT = '智汇于庄';

/** Redis Streams 常量（与 backend RedisStreamConstants.java 对齐，用于大屏流水语义） */
export const STREAM_ORDER_EVENTS = 'stream:order:events';
export const GROUP_ORDER_DISPATCH = 'group_order_dispatch';
export const CONSUMER_BACKEND = 'consumer-backend-1';

/** AI 请求超时（营销多 Agent / RAG 生成耗时较长，放宽） */
export const AI_QA_TIMEOUT = 60_000;
export const AI_MARKETING_TIMEOUT = 120_000;
