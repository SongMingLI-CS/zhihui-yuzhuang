import type { Metadata, Viewport } from 'next';
import type { ReactNode } from 'react';
import './globals.css';
import { APP_TITLE } from '@/lib/config';
import { AppShell } from '@/components/AppShell';

export const metadata: Metadata = {
  title: {
    default: APP_TITLE,
    template: `%s · 智汇于庄`,
  },
  description:
    '智汇于庄 · 面向村委会与合作社运营人员的 B 端数字产业中台与治理大脑（数据大屏 / 农技知识库 / AI 营销协同审批 / 订单出库流水）',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: '#1f4933',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
