import { useEffect, useState } from 'react';
import { WifiOff } from 'lucide-react';

export default function OfflineNotice() {
  const [online, setOnline] = useState(() => typeof navigator === 'undefined' || navigator.onLine);
  useEffect(() => {
    const sync = () => setOnline(navigator.onLine);
    addEventListener('online', sync); addEventListener('offline', sync);
    return () => { removeEventListener('online', sync); removeEventListener('offline', sync); };
  }, []);
  if (online) return null;
  return <div role="status" className="fixed inset-x-3 top-[calc(env(safe-area-inset-top)+12px)] z-[100] mx-auto flex max-w-sm items-center justify-center gap-2 rounded-xl bg-slate-900 px-3 py-2 text-xs font-semibold text-white shadow-xl"><WifiOff size={14} />当前处于离线模式，展示最近缓存内容</div>;
}
