import {
  BarChart3,
  BookOpen,
  Bot,
  Package,
  type LucideIcon,
} from 'lucide-react';

export interface NavItem {
  href: string;
  label: string;
  /** 顶栏语义化短标题 */
  short: string;
  description: string;
  icon: LucideIcon;
}

/** 侧边栏 / 顶栏共享导航元数据（href 相对 basePath '/b'） */
export const NAV_ITEMS: NavItem[] = [
  {
    href: '/dashboard',
    label: '产业治理大盘',
    short: '产业治理大盘',
    description: '经营指标 · 销售趋势 · 实时削峰流水',
    icon: BarChart3,
  },
  {
    href: '/knowledge',
    label: '农技知识库沙盒',
    short: '农技知识库沙盒',
    description: '切片文献 · RAG 检索验证 · 防幻觉溯源',
    icon: BookOpen,
  },
  {
    href: '/agents',
    label: '营销 Agent 协同台',
    short: '营销 Agent 协同台',
    description: '多智能体文案生成 · 广告法合规 · 人机审批',
    icon: Bot,
  },
  {
    href: '/orders',
    label: '订单与出库流水',
    short: '订单与出库流水',
    description: '渠道订单聚合 · 履约出库流水',
    icon: Package,
  },
];
