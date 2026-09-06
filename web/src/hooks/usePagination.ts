'use client';

import { useCallback } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

export function usePagination(defaultPageSize = 10) {
  const router = useRouter(); const pathname = usePathname(); const params = useSearchParams();
  const page = Math.max(1, Number(params.get('page')) || 1);
  const pageSize = [5, 10, 20, 50].includes(Number(params.get('pageSize'))) ? Number(params.get('pageSize')) : defaultPageSize;
  const sort = params.get('sort') ?? undefined;
  const update = useCallback((values: Record<string, string | number | undefined>) => {
    const next = new URLSearchParams(params.toString());
    Object.entries(values).forEach(([key, value]) => value == null ? next.delete(key) : next.set(key, String(value)));
    router.replace(`${pathname}?${next.toString()}`, { scroll: false });
  }, [params, pathname, router]);
  return { page, pageSize, sort, setPage: (value: number) => update({ page: Math.max(1, value) }), setPageSize: (value: number) => update({ page: 1, pageSize: value }), setSort: (value?: string) => update({ page: 1, sort: value }) };
}
