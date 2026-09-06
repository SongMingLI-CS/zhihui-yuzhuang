'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';

export function Pagination({ page, pageSize, total, onPageChange, onPageSizeChange }: { page: number; pageSize: number; total: number; onPageChange: (page: number) => void; onPageSizeChange: (size: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / pageSize)); const safePage = Math.min(page, pages);
  return <nav className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 px-4 py-3" aria-label="分页">
    <span className="text-xs text-slate-400">共 {total} 条 · 第 {safePage}/{pages} 页</span>
    <div className="flex items-center gap-2"><label className="text-xs text-slate-400">每页 <select value={pageSize} onChange={(event) => onPageSizeChange(Number(event.target.value))} className="control ml-1 h-8 px-2 text-xs" aria-label="每页条数">{[5, 10, 20, 50].map((size) => <option key={size} value={size}>{size}</option>)}</select></label><button type="button" onClick={() => onPageChange(safePage - 1)} disabled={safePage <= 1} className="grid h-8 w-8 place-items-center rounded-lg border border-slate-200 disabled:opacity-40" aria-label="上一页"><ChevronLeft size={14} /></button><button type="button" onClick={() => onPageChange(safePage + 1)} disabled={safePage >= pages} className="grid h-8 w-8 place-items-center rounded-lg border border-slate-200 disabled:opacity-40" aria-label="下一页"><ChevronRight size={14} /></button></div>
  </nav>;
}
