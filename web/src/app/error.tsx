'use client';

import { ErrorBlock } from '@/components/ui/StateView';

export default function GlobalError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="mx-auto max-w-2xl py-12">
      <ErrorBlock title="页面暂时无法加载" message="请重试；如果问题持续出现，请检查服务链路状态。" onRetry={reset} />
    </div>
  );
}
