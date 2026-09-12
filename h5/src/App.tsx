import { lazy, Suspense, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertCircle, BadgeCheck, MapPin, RefreshCw, ShoppingBasket, Sprout, Truck, Wheat } from 'lucide-react';
import TopBar from './components/TopBar';
import HeroBanner from './components/HeroBanner';
import CountdownStrip from './components/CountdownStrip';
import ProductCard from './components/ProductCard';
import type { SuccessData } from './components/OrderSuccessModal';
import Providers from './components/Providers';
import OfflineNotice from './components/OfflineNotice';
import type { OrderCheckoutResponse, Product } from './types/api';
import { fetchProducts, saveOrderCredential, toApiError } from './lib/http';
import OrderCenter from './components/OrderCenter';
import { DEMO_MODE } from './config';

const CheckoutDrawer = lazy(() => import('./components/CheckoutDrawer'));
const OrderSuccessModal = lazy(() => import('./components/OrderSuccessModal'));
const AgriQA = lazy(() => import('./components/AgriQA'));

/** 页面主体（子组件经 Context 使用 Toast） */
function ShopPage() {
  const [buyTarget, setBuyTarget] = useState<Product | null>(null);
  const [success, setSuccess] = useState<SuccessData | null>(null);
  const productsQuery = useQuery({ queryKey: ['products'], queryFn: fetchProducts });
  const products = productsQuery.data ?? [];
  const loading = productsQuery.isPending;
  const error = productsQuery.error ? toApiError(productsQuery.error).message : null;
  const reload = () => { void productsQuery.refetch(); };

  const handleBuy = (p: Product) => setBuyTarget(p);

  const handleOrderSuccess = (resp: OrderCheckoutResponse) => {
    if (buyTarget) setSuccess({ resp, product: buyTarget });
    // 保存本人订单查询凭证（仅订单号 + 一次性凭证，便于订单中心查询/取消）
    saveOrderCredential(resp.orderNo, resp.queryToken);
    setBuyTarget(null);
    reload(); // 下单后刷新实时库存
  };

  const handleSoldOut = () => {
    setBuyTarget(null);
    reload();
  };

  return (
    <div className="min-h-screen">
      <OfflineNotice />
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

      {/* 订单中心：本人订单查询 / 物流 / 取消（订单号 + 查询凭证） */}
      <OrderCenter />

      <footer className="mx-auto w-full max-w-[430px] px-4 pb-10">
        <div className="mt-6 flex items-center justify-center gap-1.5 text-[11px] text-slate-400">
          <Wheat size={12} className="text-green-500" />
          智汇于庄 · 特色产业数字助农 · 让乡村好物直达城市
        </div>
        <p className="mt-2 text-center text-[10px] leading-relaxed text-slate-400">
          {DEMO_MODE
            ? '演示模式 · 商品与订单数据用于功能演示，请勿作为真实交易依据'
            : '商品与订单数据来自平台数据库；请勿在公开场合泄露订单查询凭证'}
          <br />
          本页面经由统一网关 /h5/ 反向代理至 h5 服务
        </p>

        <details className="mt-3 rounded-xl border border-slate-200 bg-white px-3 py-2">
          <summary className="cursor-pointer text-[11px] font-medium text-slate-600">
            隐私政策与用户协议
          </summary>
          <div className="mt-2 space-y-2 text-[10px] leading-relaxed text-slate-500">
            <p>
              <b className="text-slate-600">收货信息处理说明：</b>
              下单所需的收货人姓名、手机号与收货地址仅用于订单履约与物流配送，
              存储于平台业务数据库；页面与日志不会展示完整手机号与详细门牌，
              订单查询接口返回的内容均已脱敏（如 138****0000、河南省…县…镇…）。
            </p>
            <p>
              <b className="text-slate-600">订单查询凭证：</b>
              下单成功后系统会下发一次性查询凭证（queryToken），仅保存在您的浏览器本机，
              用于本人订单查询与取消。凭证在服务端以不可逆哈希保存，
              遗失后无法找回，也不会通过短信/邮件自动发送。
            </p>
            <p>
              <b className="text-slate-600">用户协议（要点）：</b>
              本平台提供的农技问答与政策解读内容基于知识库检索，仅供参考，
              不构成官方技术指导或法律意见；支付与发票以实际渠道凭证为准。
              禁止使用平台从事虚假交易、刷单或其他违法违规行为。
            </p>
          </div>
        </details>
      </footer>
        </div>
      </div>

      {/* 确认下单抽屉 */}
      <Suspense fallback={null}><CheckoutDrawer
        product={buyTarget}
        onClose={() => setBuyTarget(null)}
        onSuccess={handleOrderSuccess}
        onSoldOut={handleSoldOut}
      /></Suspense>

      {/* 抢购成功弹窗 */}
      <Suspense fallback={null}><OrderSuccessModal data={success} onClose={() => setSuccess(null)} /></Suspense>

      {/* 农技 AI 问答悬浮球 */}
      <Suspense fallback={null}><AgriQA /></Suspense>
    </div>
  );
}

export default function App() {
  return (
    <Providers>
      <ShopPage />
    </Providers>
  );
}
