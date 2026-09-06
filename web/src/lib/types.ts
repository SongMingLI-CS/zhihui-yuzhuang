/**
 * 智汇于庄 web · 契约类型
 * 严格对齐 docs/api-spec.yaml 与 backend / ai-service 的 DTO 命名。
 */

/** 统一响应包裹（backend Spring Boot 与 ai-service FastAPI 语义一致） */
export interface ApiResponse<T> {
  code: string;
  message: string;
  data: T | null;
  timestamp: number;
  requestId: string;
}

/** 后端列表接口启用后的统一分页形状；当前 UI 可先使用同构的客户端分页。 */
export interface PageResult<T> { items: T[]; page: number; pageSize: number; total: number; totalPages: number }

/* ===================== 特产商品（GET /api/v1/products） ===================== */

export interface Product {
  id: number;
  skuCode: string;
  spuName: string;
  price: number;
  stock: number;
  status: string;
  tenantId: string;
  description: string;
  imageUrl: string;
}

/* ===================== 农技问答（POST /ai/v1/qa/ask） ===================== */

export type AgriCategory = 'DISEASE_PEST' | 'FERTILIZER' | 'POLICY' | 'GENERAL';

export interface AgriQARequest {
  question: string;
  category?: AgriCategory;
  sessionId?: string | null;
}

export interface Citation {
  docTitle: string;
  pageNumber?: number | null;
  chunkText: string;
  similarityScore: number;
}

export interface AgriQAResponse {
  answer: string;
  disclaimer?: string | null;
  citations: Citation[];
}

/* ===================== 特产营销生成（POST /ai/v1/marketing/generate） ===================== */

export type MarketingChannel = 'MOMENTS' | 'RED_BOOK' | 'LIVESTREAM';

export interface MarketingGenerateRequest {
  product_name: string;
  selling_points: string[];
  target_audience?: string | null;
  channel_preferences: MarketingChannel[];
}

export interface AgentThoughtNode {
  agent_name: string;
  output_summary: string;
}

export interface MarketingCopyItem {
  channel: MarketingChannel;
  title: string;
  content: string;
  call_to_action: string;
}

export interface ComplianceReport {
  score: number;
  passed: boolean;
  risk_terms_detected: string[];
  revision_suggestions: string[];
}

export interface MarketingGenerateResponse {
  thought_chain: AgentThoughtNode[];
  copies: MarketingCopyItem[];
  compliance: ComplianceReport;
  review_status: string;
}
