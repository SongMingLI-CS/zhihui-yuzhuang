import {
  BarChart3,
  BookOpen,
  Bot,
  Package,
  PackagePlus,
  type LucideIcon,
} from 'lucide-react';

export interface NavItem {
  href: string;
  label: string;
  /** 顶栏语义化短标题 */
  short: string;
  description: string;
  icon: LucideIcon;
  /** 可见角色（服务端仍强校验；此处仅用于菜单体验与分区隔离） */
  roles: string[];
}

const ALL_OPS = ['COOPERATIVE', 'VILLAGE', 'PLATFORM_ADMIN'];

/** 侧边栏 / 顶栏共享导航元数据（href 相对 basePath '/b'） */
export const NAV_ITEMS: NavItem[] = [
  {
    href: '/dashboard',
    label: '产业治理大盘',
    short: '产业治理大盘',
    description: '经营指标 · 销售趋势 · 真实事件流水',
    icon: BarChart3,
    roles: ['VILLAGE', 'PLATFORM_ADMIN'],
  },
  {
    href: '/products',
    label: '商品管理',
    short: '商品管理',
    description: '特产上架 · 编辑 · 上下架',
    icon: PackagePlus,
    roles: ALL_OPS,
  },
  {
    href: '/knowledge',
    label: '农技知识库沙盒',
    short: '农技知识库沙盒',
    description: '切片文献 · RAG 检索验证 · 防幻觉溯源',
    icon: BookOpen,
    roles: ['COOPERATIVE', 'VILLAGE', 'PLATFORM_ADMIN', 'GOVERNMENT'],
  },
  {
    href: '/agents',
    label: '营销 Agent 协同台',
    short: '营销 Agent 协同台',
    description: '多智能体文案生成 · 广告法合规 · 人机审批',
    icon: Bot,
    roles: ALL_OPS,
  },
  {
    href: '/orders',
    label: '订单与出库流水',
    short: '订单与出库流水',
    description: '渠道订单聚合 · 履约出库流水',
    icon: Package,
    roles: ALL_OPS,
  },
];

/** 按角色过滤可见导航（GOVERNMENT 只读不出现商品/营销/履约入口）。 */
export function navItemsForRole(role: string | null | undefined): NavItem[] {
  if (!role) return NAV_ITEMS;
  return NAV_ITEMS.filter((item) => item.roles.includes(role));
}
