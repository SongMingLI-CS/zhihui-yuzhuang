/**
 * 智汇于庄 H5 · 契约类型（严格对齐 docs/api-spec.yaml 与 backend/ai-service DTO）
 */

/** 后端统一响应包裹（backend Spring Boot 与 ai-service FastAPI 一致） */
export interface ApiResponse<T> {
  code: string;
  message: string;
  data: T | null;
  timestamp: number;
  requestId: string;
}

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

/* ===================== 特产下单（POST /api/v1/orders/checkout） ===================== */

export type OrderSource = 'H5_PRIVATE' | 'DOUYIN' | 'KUAISHOU' | 'B2B_PORTAL';

export interface OrderItemRequest {
  skuId: number;
  quantity: number;
  expectedUnitPrice: number;
}

export interface ReceiverAddress {
  recipientName: string;
  phone: string;
  detailedAddress: string;
}

export interface OrderCheckoutRequest {
  orderSource: OrderSource;
  remark?: string;
  items: OrderItemRequest[];
  receiverAddress: ReceiverAddress;
}

export interface OrderCheckoutResponse {
  orderNo: string;
  totalAmount: number;
  status: string;
  expireTime: number | null;
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
