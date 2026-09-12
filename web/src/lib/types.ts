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

export interface ProductUpsertRequest {
  skuCode: string;
  spuName: string;
  price: number;
  stock: number;
  status?: string;
}

export interface ProductStatusRequest {
  status: string;
}

/* ===================== 经营大盘（GET /api/v1/dashboard/summary） ===================== */

export interface DashboardTrendItem {
  date: string; // yyyy-MM-dd
  orderCount: number;
  salesAmount: number;
}

export interface DashboardTopProduct {
  skuId: number;
  spuName: string;
  quantity: number;
  salesAmount: number;
}

export interface DashboardSummary {
  tenantId: string;
  snapshotAt: number;
  totalOrders: number;
  totalSales: number;
  todayOrders: number;
  todaySales: number;
  pendingPayOrders: number;
  readyShipOrders: number;
  trend: DashboardTrendItem[];
  topProducts: DashboardTopProduct[];
}

/* ===================== 知识库文档管理（/ai/v1/knowledge/docs） ===================== */

export interface KnowledgeDocMeta {
  id: number;
  title: string;
  tenantId: string;
  source: string;
  category: string;
  chunks: number;
  status: 'READY';
  createdAt: string;
}

export interface KnowledgeUploadResult {
  title: string;
  tenantId: string;
  category: string;
  chunks: number;
  embeddingMode: string;
}

export interface KnowledgeDeleteResult {
  deleted: number;
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

/* ===================== 认证（POST /api/v1/auth/login） ===================== */

export interface AuthLoginRequest {
  username: string;
  password: string;
}

export interface UserInfo {
  userId: number;
  username: string;
  displayName: string;
  tenantId: string;
  role: string;
}

export interface AuthLoginResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  /** 是否要求首次登录改密（演示账号/管理员重置后为 true） */
  mustChangePassword?: boolean;
  user: UserInfo;
}

/* ===================== 订单查询 / 出库（GET /orders + POST /orders/{orderNo}/ship） ===================== */

/** 交易状态（与 OrderStatus 枚举对齐，5 态） */
export type OrderStatus =
  | 'PENDING_PAY'
  | 'STOCK_CONFIRMED'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'CANCELLED';

/** 履约状态（与 FulfillmentStatus 枚举对齐 · 出库流水维度，5 态） */
export type FulfillmentStatus =
  | 'PENDING'
  | 'PICKING'
  | 'READY'
  | 'SHIPPED'
  | 'ABNORMAL';

export type OrderSource = 'H5_PRIVATE' | 'DOUYIN' | 'KUAISHOU' | 'B2B_PORTAL';

/** GET /api/v1/orders 列表项摘要（严格对齐 OrderSummaryResponse） */
export interface OrderSummary {
  orderNo: string;
  orderSource: OrderSource;
  totalAmount: number;
  status: OrderStatus;
  fulfillmentStatus: FulfillmentStatus;
  recipientName: string;
  createdAt: string;
}

/** GET /api/v1/orders 查询参数（除分页外的枚举均可省略表示不过滤） */
export interface OrderListParams {
  page?: number;
  pageSize?: number;
  status?: OrderStatus;
  fulfillmentStatus?: FulfillmentStatus;
  keyword?: string;
  orderSource?: OrderSource;
}

/** POST /api/v1/orders/{orderNo}/ship 成功响应（返回出库后的订单摘要） */
export type OrderShipResponse = OrderSummary;

/* ===================== 平台管理（GET /api/v1/admin/*） ===================== */

export interface AdminTenant {
  id: number;
  tenantId: string;
  name: string;
  region?: string | null;
  status: string;
  tenantType?: string | null;
  parentRegion?: string | null;
  createdAt?: string | null;
}

export interface AdminUser {
  id: number;
  tenantId: string;
  username: string;
  displayName: string;
  role: string;
  phone?: string | null;
  status: string;
  mustChangePassword?: boolean | null;
  lastLoginAt?: string | null;
  createdAt?: string | null;
}

/* ===================== 政府治理（GET /api/v1/gov/summary） ===================== */

/* ===================== 营销任务台账与审批（/ai/v1/marketing/tasks） ===================== */

export type MarketingReviewStatus = 'PENDING_HUMAN_REVIEW' | 'APPROVED' | 'REJECTED';

export interface MarketingTaskItem {
  id: number;
  tenantId: string;
  productName: string;
  complianceScore: number;
  compliancePassed: boolean;
  reviewStatus: MarketingReviewStatus | string;
  createdBy: string;
  createdAt: string;
  reviewedBy: string;
  reviewedAt: string;
  reviewComment: string;
  publishedAt: string;
}

export interface MarketingTaskPage {
  items: MarketingTaskItem[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}


export interface GovScopeMeta {
  all: boolean;
  tenantCount: number;
  description: string;
}

export interface GovMetricDefinition {
  salesAmount: string;
  orderCount: string;
  cancelledOrders: string;
  version: string;
}

export interface GovTotals {
  totalOrders: number;
  totalSales: number;
  todayOrders: number;
  todaySales: number;
  pendingPayOrders: number;
  readyShipOrders: number;
}

export interface GovTenantBreakdown {
  tenantId: string;
  tenantName: string;
  region?: string | null;
  totalOrders: number;
  totalSales: number;
  todayOrders: number;
}

export interface GovSummary {
  snapshotAt: number;
  scope: GovScopeMeta;
  metrics: GovMetricDefinition;
  totals: GovTotals;
  breakdown: GovTenantBreakdown[];
}

/* ===================== 商家工作台（GET /api/v1/merchant/products） ===================== */

export interface MerchantProductParams {
  status?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}
