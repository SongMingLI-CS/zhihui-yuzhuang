'use client';

import { useQuery } from '@tanstack/react-query';
import { Landmark, Loader2, ShieldCheck } from 'lucide-react';
import { fetchGovSummary, toApiError } from '@/lib/http';

/**
 * 政府治理大屏（阶段 D，只读）。
 *
 * <p>数据来自 {@code GET /api/v1/gov/summary}：按授权范围（{@code t_gov_scope}）聚合，
 * 返回快照时间、范围与统计口径元数据；页面<b>不含</b>任何商品编辑/库存修改/订单履约/知识删除按钮，
 * 权限由服务端强制。
 */
function fmtTime(ts: number): string {
  const d = new Date(ts);
  return Number.isNaN(d.getTime()) ? '--' : d.toLocaleString('zh-CN', { hour12: false });
}

export default function GovDashboardPage() {
  const query = useQuery({ queryKey: ['gov-summary'], queryFn: fetchGovSummary });
  const data = query.data;

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-slate-900">
            <Landmark size={20} className="text-brand-600" />
            区域产业治理（只读）
          </h2>
          <p className="mt-1 text-xs text-slate-500">
            按账号授权范围聚合；仅展示汇总指标与脱敏后的租户维度，不提供任何写入操作。
          </p>
        </div>
        {data && (
          <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-[11px] text-slate-500">
            <p className="flex items-center gap-1 text-slate-600">
              <ShieldCheck size={12} className="text-brand-500" />
              数据更新时间：{fmtTime(data.snapshotAt)}
            </p>
            <p className="mt-0.5">范围：{data.scope.description}</p>
            <p className="mt-0.5">口径版本：{data.metrics.version}</p>
          </div>
        )}
      </header>

      {query.isLoading ? (
        <div className="flex items-center justify-center gap-2 py-16 text-slate-400">
          <Loader2 size={18} className="animate-spin" />
          <span className="text-sm">正在聚合授权范围数据…</span>
        </div>
      ) : query.isError ? (
        <div className="rounded-2xl border border-red-100 bg-red-50 py-12 text-center text-sm text-red-600">
          {toApiError(query.error).message}
        </div>
      ) : data ? (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Metric label="累计订单量" value={data.totals.totalOrders.toLocaleString('zh-CN')} />
            <Metric label="累计交易额（元）" value={Number(data.totals.totalSales).toLocaleString('zh-CN')} />
            <Metric label="今日订单量" value={data.totals.todayOrders.toLocaleString('zh-CN')} />
            <Metric label="今日交易额（元）" value={Number(data.totals.todaySales).toLocaleString('zh-CN')} />
            <Metric label="待支付锁定" value={data.totals.pendingPayOrders.toLocaleString('zh-CN')} />
            <Metric label="待出库订单" value={data.totals.readyShipOrders.toLocaleString('zh-CN')} />
            <Metric label="授权租户数" value={String(data.scope.tenantCount)} />
            <Metric label="范围类型" value={data.scope.all ? '全域授权' : '指定范围'} />
          </div>

          <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
            <div className="border-b border-slate-100 px-4 py-2.5 text-xs font-semibold text-slate-600">
              租户维度下钻（村 / 合作社，已脱敏）
            </div>
            {data.breakdown.length === 0 ? (
              <div className="py-10 text-center text-sm text-slate-400">
                当前授权范围内暂无可汇总租户，请联系平台管理员授权。
              </div>
            ) : (
              <table className="w-full text-left text-sm">
                <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-400">
                  <tr>
                    <th className="px-4 py-2.5">租户</th>
                    <th className="px-4 py-2.5">属地</th>
                    <th className="px-4 py-2.5">累计订单</th>
                    <th className="px-4 py-2.5">累计交易额</th>
                    <th className="px-4 py-2.5">今日订单</th>
                  </tr>
                </thead>
                <tbody>
                  {data.breakdown.map((row) => (
                    <tr key={row.tenantId} className="border-t border-slate-100">
                      <td className="px-4 py-2.5 text-slate-800">{row.tenantName}</td>
                      <td className="px-4 py-2.5 text-xs text-slate-500">{row.region || '—'}</td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-700">{row.totalOrders}</td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-700">
                        ¥{Number(row.totalSales).toLocaleString('zh-CN')}
                      </td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-700">{row.todayOrders}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <div className="rounded-2xl border border-dashed border-slate-200 bg-white/60 px-4 py-3 text-[11px] leading-relaxed text-slate-500">
            <p className="font-semibold text-slate-600">统计口径</p>
            <p className="mt-1">· 交易额：{data.metrics.salesAmount}</p>
            <p>· 订单量：{data.metrics.orderCount}</p>
            <p>· 取消单：{data.metrics.cancelledOrders}</p>
          </div>
        </>
      ) : null}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
      <p className="text-[11px] text-slate-400">{label}</p>
      <p className="mt-1 text-xl font-bold tabular-nums text-slate-900">{value}</p>
    </div>
  );
}
