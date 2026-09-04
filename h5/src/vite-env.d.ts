/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 业务 API 前缀（网关同源反代），默认 /api/v1 */
  readonly VITE_API_BASE?: string;
  /** AI 农技服务前缀（网关同源反代），默认 /ai/v1 */
  readonly VITE_AI_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
