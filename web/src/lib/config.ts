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

export const APP_TITLE = '智汇于庄 · 数字产业中台与治理大脑';
export const APP_SHORT = '智汇于庄';

/** Redis Streams 常量（与 backend RedisStreamConstants.java 对齐，用于大屏流水语义） */
export const STREAM_ORDER_EVENTS = 'stream:order:events';
export const GROUP_ORDER_DISPATCH = 'group_order_dispatch';
export const CONSUMER_BACKEND = 'consumer-backend-1';

/** AI 请求超时（营销多 Agent / RAG 生成耗时较长，放宽） */
export const AI_QA_TIMEOUT = 60_000;
export const AI_MARKETING_TIMEOUT = 120_000;
