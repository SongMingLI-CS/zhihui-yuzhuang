/**
 * 智汇于庄 H5 · 全局常量与环境配置
 *
 * - TENANT_ID：Header X-Tenant-Id（严格对齐 docs/api-spec.yaml 示例 tenant_yuzhuang_001）
 * - API_BASE / AI_BASE：默认走网关同源反代（/api/v1、/ai/v1），可用环境变量覆盖
 * - DEMO_MODE：由 VITE_DEMO_MODE 决定；**只有显式为 true 才显示“一键填入演示地址”按钮**
 *   （生产构建不配置该变量 → 按钮不出现，避免把演示数据当作真实下单信息）
 */
export const TENANT_ID = 'tenant_yuzhuang_001';

export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1';
export const AI_BASE = import.meta.env.VITE_AI_BASE ?? '/ai/v1';
export const AI_STREAMING_ENABLED = import.meta.env.VITE_AI_STREAMING_ENABLED === 'true';

/** 演示模式开关：仅显式 VITE_DEMO_MODE=true 时为真（生产默认关闭） */
export const DEMO_MODE = import.meta.env.VITE_DEMO_MODE === 'true';

/** 下单渠道来源（枚举严格对齐契约 H5_PRIVATE / DOUYIN / KUAISHOU / B2B_PORTAL） */
export const ORDER_SOURCE = 'H5_PRIVATE' as const;

/** 演示收货信息（仅 DEMO_MODE=true 时可一键填入） */
export const DEMO_RECEIVER = {
  recipientName: '于庄乡村振兴服务站',
  phone: '13800000000',
  detailedAddress: '河南省周口市鹿邑县试量镇于庄村乡村振兴服务站',
} as const;

/** 本地保存的订单查询凭证（键：订单号；值：一次性 queryToken） */
export const ORDER_CREDENTIALS_KEY = 'yuzhuang.guest_orders';

/** 农技咨询快捷提问 */
export const AGRI_QUICK_QUESTIONS = [
  '冬小麦发黄怎么办？',
  '香油怎么辨别真伪？',
  '小麦返青期怎么施肥？',
] as const;
