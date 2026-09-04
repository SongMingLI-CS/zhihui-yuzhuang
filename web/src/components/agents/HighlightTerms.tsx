'use client';

import { Fragment, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

/** 返回高亮片段数组：按词长降序扫描，避免「天下第一 / 第一」类短词吞长词 */
export function splitHits(text: string, terms: string[]): ReactNode[] {
  const sorted = terms.filter(Boolean).sort((a, b) => b.length - a.length);
  const out: ReactNode[] = [];
  if (!sorted.length) {
    out.push(<Fragment key={0}>{text}</Fragment>);
    return out;
  }
  let rest = text;
  let guard = 0;
  while (rest.length && guard < 500) {
    guard += 1;
    let bestIdx = -1;
    let bestTerm = '';
    for (const t of sorted) {
      const idx = rest.indexOf(t);
      if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
        bestIdx = idx;
        bestTerm = t;
      }
    }
    if (bestIdx < 0) {
      out.push(<Fragment key={out.length}>{rest}</Fragment>);
      break;
    }
    if (bestIdx > 0) {
      out.push(<Fragment key={out.length}>{rest.slice(0, bestIdx)}</Fragment>);
    }
    out.push(
      <mark key={out.length} className="rounded bg-red-100 px-0.5 py-px text-red-600">
        {bestTerm}
      </mark>,
    );
    rest = rest.slice(bestIdx + bestTerm.length);
  }
  return out;
}

/** 文本 + 命中词高亮（无命中则原样输出） */
export function HighlightTerms({
  text,
  terms,
  className,
}: {
  text: string;
  terms: string[];
  className?: string;
}) {
  return <span className={cn(className)}>{splitHits(text, terms)}</span>;
}
