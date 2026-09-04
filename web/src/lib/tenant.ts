import type { MarketingChannel } from './types';

/**
 * 租户轻量外部存储：供 axios 请求拦截器与顶栏租户切换器共享当前租户。
 * （React Context 不适用——拦截器运行在组件树之外。）
 */

export interface TenantInfo {
  id: string;
  name: string;
  region: string;
}

/** 默认租户：鹿邑试量镇于庄村股份经济合作社（契约示例 tenant_yuzhuang_001） */
export const DEFAULT_TENANT: TenantInfo = {
  id: 'tenant_yuzhuang_001',
  name: '鹿邑试量镇于庄村股份经济合作社',
  region: '河南省周口市鹿邑县试量镇',
};

export const TENANT_OPTIONS: TenantInfo[] = [
  DEFAULT_TENANT,
  {
    id: 'tenant_wangzhuang_002',
    name: '试量镇王庄种植专业合作社',
    region: '河南省周口市鹿邑县试量镇',
  },
  {
    id: 'tenant_liuzhuang_003',
    name: '观堂镇刘庄集体经济联合社',
    region: '河南省周口市鹿邑县观堂镇',
  },
];

type Listener = (tenant: TenantInfo) => void;

let current: TenantInfo = DEFAULT_TENANT;
const listeners = new Set<Listener>();

export function getTenant(): TenantInfo {
  return current;
}

export function setTenant(tenant: TenantInfo): void {
  current = tenant;
  listeners.forEach((fn) => fn(tenant));
}

export function subscribeTenant(fn: Listener): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** 渠道中文标签（与 ai-service schemas/marketing.py CHANNEL_LABELS 对齐） */
export const CHANNEL_LABELS: Record<MarketingChannel, string> = {
  MOMENTS: '微信朋友圈',
  RED_BOOK: '小红书',
  LIVESTREAM: '直播口播',
};
