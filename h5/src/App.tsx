import { useCallback, useEffect, useState } from 'react';
import { AlertCircle, BadgeCheck, MapPin, RefreshCw, ShoppingBasket, Sprout, Truck, Wheat } from 'lucide-react';
import TopBar from './components/TopBar';
import HeroBanner from './components/HeroBanner';
import CountdownStrip from './components/CountdownStrip';
import ProductCard from './components/ProductCard';
import CheckoutDrawer from './components/CheckoutDrawer';
import OrderSuccessModal, { type SuccessData } from './components/OrderSuccessModal';
import AgriQA from './components/AgriQA';
import { ToastProvider } from './components/Toast';
import type { OrderCheckoutResponse, Product } from './types/api';
import { fetchProducts, toApiError } from './lib/http';

/** 页面主体（子组件经 Context 使用 Toast） */
function ShopPage() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [buyTarget, setBuyTarget] = useState<Product | null>(null);
  const [success, setSuccess] = useState<SuccessData | null>(null);

  const reload = useCallback(() => setRefreshKey((k) => k + 1), []);

  // 拉取在售特产（GET /api/v1/products，X-Tenant-Id 由 http 层自动注入）
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchProducts()
      .then((list) => {
        if (!cancelled) setProducts(list);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setProducts([]);
          setError(toApiError(err).message);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  const handleBuy = (p: Product) => setBuyTarget(p);

  const handleOrderSuccess = (resp: OrderCheckoutResponse) => {
    if (buyTarget) setSuccess({ resp, product: buyTarget });
    setBuyTarget(null);
    reload(); // 下单后刷新实时库存
  };

  const handleSoldOut = () => {
    setBuyTarget(null);
    reload();
  };

  return (
    <div className="min-h-screen">
      <TopBar />

      <div className="shop-layout">
        <aside className="origin-story" aria-label="于庄助农项目介绍">
          <span className="inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/10 px-3 py-1.5 text-xs font-semibold text-emerald-50"><Sprout size={14} />河南 · 周口 · 鹿邑</span>
          <p className="mt-8 text-xs font-semibold uppercase tracking-[0.24em] text-amber-300">FROM FIELD TO TABLE</p>
          <h1 className="mt-3 text-4xl font-bold leading-[1.18] tracking-[-0.04em] text-white">一份好物，<br />一条更短的助农链路。</h1>
          <p className="mt-5 max-w-md text-sm leading-7 text-emerald-100/80">由合作社直接连接消费者，让传统工艺、当季农产和真实库存，在同一个轻量入口里被看见。</p>
          <div className="mt-9 grid max-w-md grid-cols-3 gap-3">
            <span className="origin-stat"><BadgeCheck size={18} />产地认证</span>
            <span className="origin-stat"><Truck size={18} />合作社直发</span>
            <span className="origin-stat"><MapPin size={18} />全程可溯源</span>
          </div>
        </aside>
        <div className="shop-column">
      <main className="mx-auto w-full max-w-[430px] pb-20">
        <HeroBanner />
        <CountdownStrip />

        {/* 商品流 */}
        <section className="px-4 pt-4">
          <div className="flex items-end justify-between">
            <div>
              <h2 className="flex items-center gap-1.5 text-[17px] font-extrabold text-slate-800">
                <ShoppingBasket size={18} className="text-green-600" />
                今日助农秒杀专场
              </h2>
              <p className="mt-1 text-[11px] text-slate-400">产地直供 · 限量抢购 · 先到先得</p>
            </div>
            <button
              type="button"
              onClick={reload}
              className="flex items-center gap-1 rounded-lg bg-white px-2 py-1 text-[11px] font-medium text-slate-500 ring-1 ring-slate-200 active:scale-95"
            >
              <RefreshCw size={12} />
              刷新库存
            </button>
          </div>

          {loading ? (
            <div className="mt-3 space-y-3">
              {[0, 1, 2].map((i) => (
                <div key={i} className="overflow-hidden rounded-2xl bg-white ring-1 ring-slate-900/5">
                  <div className="aspect-[5/3] w-full animate-shimmer bg-gradient-to-r from-slate-100 via-slate-200/70 to-slate-100 bg-[length:200%_100%]" />
                  <div className="space-y-2 p-3.5">
                    <div className="h-3 w-24 rounded bg-slate-100" />
                    <div className="h-4 w-3/4 rounded bg-slate-100" />
                    <div className="flex justify-between pt-1">
                      <div className="h-6 w-16 rounded bg-slate-100" />
                      <div className="h-6 w-20 rounded bg-slate-100" />
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : error ? (
            <div className="mt-4 rounded-2xl bg-white p-6 text-center ring-1 ring-slate-100">
              <AlertCircle size={28} className="mx-auto text-red-400" />
              <p className="mt-2 text-[13px] leading-relaxed text-slate-500">
                加载失败：{error}
                <br />
                请确认后端服务已启动
              </p>
              <button
                type="button"
                onClick={reload}
                className="mt-4 flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-green-600 to-emerald-500 px-5 py-2 text-[13px] font-bold text-white shadow-md shadow-green-600/20 active:scale-95"
              >
                <RefreshCw size={14} />
                重新加载
              </button>
            </div>
          ) : products.length === 0 ? (
            <div className="mt-4 rounded-2xl bg-white p-8 text-center ring-1 ring-slate-100">
              <Wheat size={30} className="mx-auto text-green-400" />
              <p className="mt-2 text-[14px] font-semibold text-slate-500">本场暂无在售特产</p>
              <p className="mt-1 text-[11px] text-slate-400">敬请关注下一场助农特惠</p>
            </div>
          ) : (
            <div className="mt-3 space-y-3">
              {products.map((p, i) => (
                <ProductCard key={p.id} product={p} index={i} onBuy={handleBuy} />
              ))}
            </div>
          )}
        </section>
      </main>

      <footer className="mx-auto w-full max-w-[430px] px-4 pb-10">
        <div className="mt-2 flex items-center justify-center gap-1.5 text-[11px] text-slate-400">
          <Wheat size={12} className="text-green-500" />
          智汇于庄 · 特色产业数字助农 · 让乡村好物直达城市
        </div>
        <p className="mt-2 text-center text-[10px] leading-relaxed text-slate-300">
          演示环境 · 商品与订单数据仅供乡村振兴竞赛演示
          <br />
          本页面经由统一网关 /h5/ 反向代理至 h5 服务
        </p>
      </footer>
        </div>
      </div>

      {/* 确认下单抽屉 */}
      <CheckoutDrawer
        product={buyTarget}
        onClose={() => setBuyTarget(null)}
        onSuccess={handleOrderSuccess}
        onSoldOut={handleSoldOut}
      />

      {/* 抢购成功弹窗 */}
      <OrderSuccessModal data={success} onClose={() => setSuccess(null)} />

      {/* 农技 AI 问答悬浮球 */}
      <AgriQA />
    </div>
  );
}

export default function App() {
  return (
    <ToastProvider>
      <ShopPage />
    </ToastProvider>
  );
}
