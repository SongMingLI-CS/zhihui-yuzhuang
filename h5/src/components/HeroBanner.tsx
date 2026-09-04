import { BadgeCheck, MapPin, Rocket, Wheat } from 'lucide-react';

/** 乡村振兴氛围横幅：标题 / 副标 / 信任标签 */
export default function HeroBanner() {
  return (
    <section className="relative mx-auto w-full max-w-[430px] overflow-hidden">
      <div className="relative mx-4 mt-3 overflow-hidden rounded-2xl bg-gradient-to-br from-green-600 via-emerald-600 to-lime-600 px-5 py-5 text-white shadow-lg shadow-green-700/20">
        {/* 装饰 */}
        <div className="pointer-events-none absolute -right-6 -top-8 h-28 w-28 rounded-full bg-white/10" />
        <div className="pointer-events-none absolute -bottom-10 -right-2 h-24 w-24 rounded-full bg-lime-300/20" />
        <div className="pointer-events-none absolute -left-3 top-1/2 -translate-y-1/2 text-[64px] opacity-15">
          🌾
        </div>

        <div className="relative">
          <span className="inline-flex items-center gap-1 rounded-full bg-amber-400/20 px-2 py-0.5 text-[10px] font-semibold tracking-widest text-amber-100 ring-1 ring-amber-200/30">
            <BadgeCheck size={11} />
            乡村振兴示范专场
          </span>

          <h1 className="mt-2 text-[22px] font-extrabold leading-tight">
            智汇于庄 · 特色产业数字助农
          </h1>
          <p className="mt-1.5 flex items-center gap-1 text-[13px] text-green-50/95">
            <MapPin size={13} className="shrink-0 text-amber-200" />
            周口鹿邑试量镇特产直供 · 产地合作社直发
          </p>

          <div className="mt-3.5 flex flex-wrap items-center gap-2 text-[11px]">
            <span className="flex items-center gap-1 rounded-lg bg-white/15 px-2 py-1 ring-1 ring-white/20">
              <Rocket size={11} className="text-amber-300" />
              今发速配
            </span>
            <span className="flex items-center gap-1 rounded-lg bg-white/15 px-2 py-1 ring-1 ring-white/20">
              <Wheat size={11} className="text-lime-200" />
              非遗工艺
            </span>
            <span className="flex items-center gap-1 rounded-lg bg-white/15 px-2 py-1 ring-1 ring-white/20">
              <BadgeCheck size={11} className="text-emerald-200" />
              助农直供价
            </span>
          </div>
        </div>
      </div>
    </section>
  );
}
