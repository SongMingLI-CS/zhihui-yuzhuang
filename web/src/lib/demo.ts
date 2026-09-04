import { lastNDays } from './format';

/**
 * 大屏 / 表格 / 流水演示数据集（前端占位快照）。
 *
 * 说明：backend 目前仅发布 /orders/checkout、/products 与 /healthz 只读端点，
 * 尚未提供「经营大盘聚合」「知识库文档列表」「订单分页」等管理端只读接口。
 * 为让 B 端大屏与表格先可视化交付，此处按契约字段命名给出代表性子集；
 * 待对应聚合接口在 backend/ai-service 就绪后，由各页面的真实请求原位替换即可。
 * 所有『流式事件』均为客户端对 Redis Streams 消费语义的模拟（无 SSE/WS 推送端点）。
 */
export const DEMO_NOTE = '演示快照 · 待对应聚合接口就绪后自动替换为实时数据';

/* ===================== 顶部指标卡 ===================== */

export interface MetricSnapshot {
  /** 累计助农销售额（元） */
  cumulativeSales: number;
  /** 今日订单数 */
  todayOrders: number;
  /** 农技 RAG 服务人次 */
  ragCalls: number;
  /** Outbox 削峰事件吞吐（条/分钟） */
  outboxTps: number;
}

export const METRICS: MetricSnapshot = {
  cumulativeSales: 12_864_300,
  todayOrders: 326,
  ragCalls: 1_284,
  outboxTps: 148,
};

/* ===================== 图表 A：近 7 日订单 / 营收趋势 ===================== */

export interface TrendPoint {
  label: string;
  orders: number;
  revenue: number;
}

const ORDERS_SERIES = [208, 236, 219, 268, 245, 289, 326];
const REVENUE_SERIES = [14200, 16100, 15050, 18400, 16800, 19900, 22600];

/** 生成近 n 日（含今日）趋势点，末位即今日快照 */
export function buildTrend(n = 7): TrendPoint[] {
  const labels = lastNDays(n);
  return labels.map((label, i) => ({
    label,
    orders: ORDERS_SERIES[i % ORDERS_SERIES.length],
    revenue: REVENUE_SERIES[i % REVENUE_SERIES.length],
  }));
}

/* ===================== 图表 B：于庄特色产品销售占比 ===================== */

export interface SalesShareItem {
  name: string;
  value: number;
  amount: number;
  color: string;
}

export const SALES_SHARE: SalesShareItem[] = [
  { name: '小磨香油', value: 46.2, amount: 5_943_300, color: '#2c724a' },
  { name: '富硒面粉', value: 33.5, amount: 4_310_000, color: '#e9b949' },
  { name: '土蜂蜜', value: 20.3, amount: 2_611_000, color: '#8fc9a2' },
];

/* ===================== 知识库：已切片文献列表（演示） ===================== */

export type DocStatus = 'READY' | 'PROCESSING' | 'FAILED';

export interface KnowledgeDoc {
  id: number;
  title: string;
  tenantId: string;
  tenantName: string;
  chunks: number;
  status: DocStatus;
  createdAt: string;
  source: string;
}

export const KNOWLEDGE_DOCS: KnowledgeDoc[] = [
  {
    id: 1,
    title: '于庄小麦种植指南',
    tenantId: 'global',
    tenantName: '全局知识库',
    chunks: 24,
    status: 'READY',
    createdAt: '2026-08-30 09:12',
    source: 'ai-service/data/raw_docs/yuzhuang_wheat_guide.txt',
  },
  {
    id: 2,
    title: '鹿邑县小麦常见真菌病防治规范',
    tenantId: 'tenant_yuzhuang_001',
    tenantName: '鹿邑试量镇于庄村股份经济合作社',
    chunks: 18,
    status: 'READY',
    createdAt: '2026-08-28 15:40',
    source: 'disease_prevention_spec.pdf',
  },
  {
    id: 3,
    title: '豫东平原冬小麦水肥管理手册',
    tenantId: 'global',
    tenantName: '全局知识库',
    chunks: 32,
    status: 'READY',
    createdAt: '2026-08-25 11:03',
    source: 'wheat_water_fertilizer_manual.pdf',
  },
  {
    id: 4,
    title: '河南省乡村振兴特色产业扶持政策汇编',
    tenantId: 'global',
    tenantName: '全局知识库',
    chunks: 45,
    status: 'READY',
    createdAt: '2026-08-18 17:26',
    source: 'revitalization_policy_2026.pdf',
  },
  {
    id: 5,
    title: '小磨香油传统工艺与质量鉴别（初稿）',
    tenantId: 'tenant_yuzhuang_001',
    tenantName: '鹿邑试量镇于庄村股份经济合作社',
    chunks: 12,
    status: 'PROCESSING',
    createdAt: '2026-09-04 08:30',
    source: 'sesame_oil_craft_draft.pdf',
  },
];

/** 农技检索验证·示例问题 */
export const AGRI_QUICK_QUESTIONS = [
  '冬小麦发黄纹枯病如何防治？',
  '小麦返青期应该如何追肥？',
  '富硒面粉的硒含量标准是什么？',
];

/* ===================== 订单 / 出库流水（演示） ===================== */

export type OrderStatus =
  | 'PENDING_PAY'
  | 'STOCK_CONFIRMED'
  | 'PICKING'
  | 'READY'
  | 'SHIPPED'
  | 'ABNORMAL';

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAY: '待支付',
  STOCK_CONFIRMED: '库存已确认',
  PICKING: '拣货中',
  READY: '出库就绪',
  SHIPPED: '已出库',
  ABNORMAL: '异常',
};

export const CHANNEL_LABELS_SHORT: Record<string, string> = {
  H5_PRIVATE: '私域 H5',
  DOUYIN: '抖音',
  KUAISHOU: '快手',
  B2B_PORTAL: 'B2B 集采',
};

export interface OrderRow {
  id: number;
  orderNo: string;
  createdAt: string;
  source: string;
  skuName: string;
  qty: number;
  amount: number;
  status: OrderStatus;
}

export const ORDERS: OrderRow[] = [
  { id: 1, orderNo: 'ORD202609040128', createdAt: '2026-09-04 11:52', source: 'H5_PRIVATE', skuName: '于庄荆条土蜂蜜', qty: 2, amount: 256.0, status: 'READY' },
  { id: 2, orderNo: 'ORD202609040117', createdAt: '2026-09-04 11:48', source: 'DOUYIN', skuName: '于庄传统石磨小磨香油', qty: 4, amount: 272.0, status: 'PICKING' },
  { id: 3, orderNo: 'ORD202609040102', createdAt: '2026-09-04 11:35', source: 'KUAISHOU', skuName: '于庄富硒石磨小麦粉', qty: 10, amount: 399.0, status: 'STOCK_CONFIRMED' },
  { id: 4, orderNo: 'ORD202609040098', createdAt: '2026-09-04 11:21', source: 'H5_PRIVATE', skuName: '于庄传统石磨小磨香油', qty: 2, amount: 136.0, status: 'SHIPPED' },
  { id: 5, orderNo: 'ORD202609040089', createdAt: '2026-09-04 11:05', source: 'B2B_PORTAL', skuName: '于庄富硒石磨小麦粉', qty: 60, amount: 2394.0, status: 'SHIPPED' },
  { id: 6, orderNo: 'ORD202609040076', createdAt: '2026-09-04 10:47', source: 'H5_PRIVATE', skuName: '于庄荆条土蜂蜜', qty: 1, amount: 128.0, status: 'READY' },
  { id: 7, orderNo: 'ORD202609040055', createdAt: '2026-09-04 10:22', source: 'DOUYIN', skuName: '于庄传统石磨小磨香油', qty: 8, amount: 544.0, status: 'PICKING' },
  { id: 8, orderNo: 'ORD202609040031', createdAt: '2026-09-04 09:58', source: 'KUAISHOU', skuName: '于庄富硒石磨小麦粉', qty: 6, amount: 239.4, status: 'ABNORMAL' },
  { id: 9, orderNo: 'ORD202609040019', createdAt: '2026-09-04 09:36', source: 'H5_PRIVATE', skuName: '于庄荆条土蜂蜜', qty: 3, amount: 384.0, status: 'SHIPPED' },
  { id: 10, orderNo: 'ORD202609040008', createdAt: '2026-09-04 09:12', source: 'B2B_PORTAL', skuName: '于庄传统石磨小磨香油', qty: 120, amount: 8160.0, status: 'SHIPPED' },
];

/* ===================== 实时流水事件（Redis Streams 消费语义模拟） ===================== */

export type StreamLevel = 'outbox' | 'stream' | 'pick' | 'ready' | 'outbound' | 'error' | 'info';

export interface StreamEvent {
  id: string;
  time: string;
  level: StreamLevel;
  text: string;
}

/** 营销生成器默认示例 */
export const PRODUCT_PRESETS = [
  {
    name: '于庄传统石磨小磨香油',
    points: ['古法石磨初榨', '无任何添加剂', '芝麻原香浓郁'],
    audience: '注重食品健康的家庭主妇',
  },
  {
    name: '于庄富硒石磨小麦粉',
    points: ['豫东富硒带原料', '低温石磨保留麦香', '适合家庭烘焙'],
    audience: '追求健康主食的年轻家庭',
  },
  {
    name: '于庄荆条土蜂蜜',
    points: ['深山荆条花源', '波美度≥42°', '自然成熟蜜'],
    audience: '注重滋补养生的人群',
  },
];
