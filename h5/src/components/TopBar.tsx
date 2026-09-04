import { Leaf, MapPin, Sprout } from 'lucide-react';

/** 顶栏：品牌 + 属地标识（悬浮于滚动内容之上） */
export default function TopBar() {
  return (
    <header className="sticky top-0 z-40 bg-gradient-to-r from-green-800 via-green-700 to-emerald-600 text-white shadow-md shadow-green-900/10">
      <div className="mx-auto flex w-full max-w-[430px] items-center justify-between px-4 py-2.5 safe-top">
        <div className="flex items-center gap-2">
          <span className="grid h-8 w-8 place-items-center rounded-xl bg-white/15 ring-1 ring-white/25">
            <Leaf size={18} strokeWidth={2.2} />
          </span>
          <div className="leading-tight">
            <div className="flex items-center gap-1.5 text-[15px] font-bold tracking-wide">
              智汇于庄
              <Sprout size={13} className="text-lime-300" />
            </div>
            <div className="text-[10px] text-green-100/90">特色产业数字助农</div>
          </div>
        </div>
        <span className="flex items-center gap-1 rounded-full bg-white/15 px-2.5 py-1 text-[11px] font-medium ring-1 ring-white/20">
          <MapPin size={11} className="text-amber-300" />
          周口 · 鹿邑
        </span>
      </div>
    </header>
  );
}
