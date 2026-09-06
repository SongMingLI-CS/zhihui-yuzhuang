import { SkeletonBlock } from '@/components/ui/StateView';

export default function Loading() {
  return (
    <div className="page-stack" aria-busy="true">
      <div className="h-32 animate-pulse rounded-[22px] bg-white/70" />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }, (_, index) => <div key={index} className="panel h-28 animate-pulse bg-white/80" />)}
      </div>
      <div className="panel p-5"><SkeletonBlock rows={6} /></div>
    </div>
  );
}
