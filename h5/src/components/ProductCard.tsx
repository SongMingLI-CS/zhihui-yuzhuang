import { ShoppingBag, Zap } from 'lucide-react';
import type { Product } from '../types/api';
import { productTags, stockMeta, tagClass } from '../lib/productMeta';
import ProductVisual from './ProductVisual';

/** 大字价格：¥ + 整数 + 小数（可跨卡片/抽屉/弹窗复用） */
export function Price({ value, big = false }: { value: number; big?: boolean }) {
  const [int, dec] = Number(value).toFixed(2).split('.');
  return (
    <span className="inline-flex items-baseline text-red-600">
      <span className={`${big ? 'text-lg' : 'text-[13px]'} font-extrabold`}>¥</span>
      <span
        className={`font-digit font-extrabold leading-none tracking-tight ${
          big ? 'text-[34px]' : 'text-[26px]'
        }`}
      >
        {int}
      </span>
      <span className={`${big ? 'text-lg' : 'text-[13px]'} font-extrabold`}>.{dec}</span>
    </span>
  );
}

interface ProductCardProps {
  product: Product;
  index: number;
  onBuy: (p: Product) => void;
}

/** 特产商品卡：图片(带占位兜底) / 标签 / 名称 / 描述 / 大字价 + 实时库存 / 立即抢购 */
export default function ProductCard({ product, index, onBuy }: ProductCardProps) {
  const tags = productTags(product);
  const stock = stockMeta(product);
  const soldOut = stock.soldOut;

  return (
    <article
      className="animate-fade-in overflow-hidden rounded-[20px] border border-green-950/[0.07] bg-white shadow-[0_12px_32px_rgba(36,71,46,.07)]"
      style={{ animationDelay: `${Math.min(index, 6) * 70}ms` }}
    >
      {/* 商品图：后端 imageUrl 为演示占位域名，加载失败回退 emoji 底图 */}
      <div className="relative aspect-[5/3] w-full overflow-hidden bg-gradient-to-br from-green-50 via-emerald-50 to-amber-50">
        <ProductVisual product={product} />
        {soldOut && (
          <div className="absolute inset-0 grid place-items-center bg-slate-900/45 backdrop-blur-[1px]">
            <span className="rounded-full bg-white px-4 py-1 text-[13px] font-bold text-slate-700">
              已售罄
            </span>
          </div>
        )}
      </div>

      <div className="p-3.5">
        <div className="flex flex-wrap items-center gap-1.5">
          {tags.map((t) => (
            <span
              key={t.text}
              className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${tagClass(t.tone)}`}
            >
              {t.text}
            </span>
          ))}
        </div>

        <h3 className="mt-2 line-clamp-1 text-[15px] font-bold text-slate-800">{product.spuName}</h3>
        <p className="mt-1 line-clamp-2 min-h-[34px] text-[12px] leading-relaxed text-slate-500">
          {product.description || '产地合作社直供 · 助力乡村振兴'}
        </p>

        <div className="mt-2.5 flex items-center justify-between">
          <Price value={product.price} />
          <span
            className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${
              soldOut
                ? 'bg-slate-100 text-slate-400'
                : stock.low
                  ? 'bg-red-50 text-red-600'
                  : 'bg-emerald-50 text-emerald-700'
            }`}
          >
            {stock.text}
          </span>
        </div>

        <button
          type="button"
          disabled={soldOut}
          onClick={() => onBuy(product)}
          className="mt-3 flex min-h-11 w-full items-center justify-center gap-1.5 rounded-xl bg-green-700 py-2.5 text-[14px] font-bold text-white shadow-[0_8px_18px_rgba(21,128,61,.16)] transition hover:bg-green-800 active:scale-[0.98] disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400 disabled:shadow-none"
        >
          {soldOut ? <ShoppingBag size={15} /> : <Zap size={15} />}
          {soldOut ? '已抢光' : '立即抢购'}
        </button>
      </div>
    </article>
  );
}
