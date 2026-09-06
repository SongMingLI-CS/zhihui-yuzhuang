import type { Product } from '../types/api';

function kindOf(product: Product): 'oil' | 'flour' | 'noodles' {
  const name = product.spuName;
  if (name.includes('香油')) return 'oil';
  if (name.includes('小麦') || name.includes('面粉')) return 'flour';
  return 'noodles';
}

export default function ProductVisual({ product, compact = false }: { product: Product; compact?: boolean }) {
  const kind = kindOf(product);
  return (
    <div className={`relative grid h-full w-full place-items-center overflow-hidden bg-[#edf4e9] ${compact ? 'rounded-xl' : ''}`} aria-hidden="true">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_70%_20%,rgba(255,255,255,.9),transparent_32%),linear-gradient(145deg,rgba(31,110,67,.05),rgba(203,154,56,.08))]" />
      <svg viewBox="0 0 260 180" className={`relative drop-shadow-[0_14px_14px_rgba(35,70,45,.16)] ${compact ? 'h-[88%] w-[88%]' : 'h-[78%] w-[78%]'}`}>
        {kind === 'oil' && (
          <>
            <ellipse cx="130" cy="153" rx="48" ry="9" fill="#234f38" opacity=".12" />
            <path d="M102 42h56l-5 18v75c0 12-9 20-20 20h-6c-11 0-20-8-20-20V60z" fill="#f7ead0" stroke="#315c43" strokeWidth="3" />
            <path d="M108 89h44v45c0 9-6 15-15 15h-14c-9 0-15-6-15-15z" fill="#b87424" opacity=".82" />
            <path d="M104 34h52v15h-52z" rx="5" fill="#2d5f42" />
            <path d="M113 68h34" stroke="#d6ad56" strokeWidth="4" strokeLinecap="round" />
            <circle cx="130" cy="109" r="17" fill="#fff7dc" opacity=".9" />
            <path d="M120 112c10-2 15-8 19-16-1 11-6 20-15 25" fill="none" stroke="#4f7a50" strokeWidth="3" strokeLinecap="round" />
          </>
        )}
        {kind === 'flour' && (
          <>
            <ellipse cx="130" cy="153" rx="54" ry="9" fill="#234f38" opacity=".12" />
            <path d="M82 47c20-12 76-12 96 0l-8 96c-19 12-61 12-80 0z" fill="#f5eddb" stroke="#42664c" strokeWidth="3" />
            <path d="M84 52c26 8 66 8 92 0" fill="none" stroke="#cf9f3e" strokeWidth="7" />
            <path d="M96 72h68v46H96z" rx="8" fill="#2f704b" opacity=".95" />
            <path d="M130 107V82m0 9c-10-2-15-7-18-13m18 18c10-2 15-7 18-13" fill="none" stroke="#f3d58c" strokeWidth="3" strokeLinecap="round" />
            <path d="M106 129h48" stroke="#c8aa70" strokeWidth="3" strokeLinecap="round" />
          </>
        )}
        {kind === 'noodles' && (
          <>
            <ellipse cx="130" cy="151" rx="58" ry="9" fill="#234f38" opacity=".12" />
            <path d="M91 51c28-10 50-10 78 0l-7 90c-21 8-43 8-64 0z" fill="#fbf2dc" stroke="#4e684f" strokeWidth="3" />
            <path d="M99 65h62v57H99z" rx="10" fill="#b96237" opacity=".88" />
            <path d="M111 76c8 15 8 30 0 44m13-44c8 15 8 30 0 44m13-44c8 15 8 30 0 44m13-44c8 15 8 30 0 44" fill="none" stroke="#f6d59a" strokeWidth="4" strokeLinecap="round" />
            <path d="M96 45h68" stroke="#cc9e42" strokeWidth="8" strokeLinecap="round" />
          </>
        )}
      </svg>
      {!compact && <span className="absolute bottom-4 right-4 rounded-full border border-white/80 bg-white/70 px-3 py-1 text-[10px] font-semibold tracking-wider text-green-800 shadow-sm backdrop-blur">于庄产地直供</span>}
    </div>
  );
}
