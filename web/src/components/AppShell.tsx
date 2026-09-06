'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';
import { ToastProvider } from './ui/Toast';

/** 全局壳：左侧深色导航 + 右侧顶栏/内容区 */
export function AppShell({ children }: { children: ReactNode }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false);
        menuButtonRef.current?.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [menuOpen]);

  return (
    <ToastProvider>
      <div className="flex h-dvh overflow-hidden bg-[var(--canvas)]">
        <div className="hidden lg:block"><Sidebar /></div>
        {menuOpen && (
          <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true" aria-label="主导航">
            <button
              type="button"
              className="absolute inset-0 bg-slate-950/40 backdrop-blur-[2px]"
              onClick={() => { setMenuOpen(false); menuButtonRef.current?.focus(); }}
              aria-label="关闭导航"
            />
            <div className="relative h-full w-[244px] max-w-[84vw] animate-fade-in">
              <Sidebar mobile onNavigate={() => { setMenuOpen(false); menuButtonRef.current?.focus(); }} />
            </div>
          </div>
        )}
        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar menuButtonRef={menuButtonRef} onMenuOpen={() => setMenuOpen(true)} />
          <main className="min-h-0 flex-1 overflow-y-auto px-3 py-3 sm:px-4 sm:py-4 lg:px-6 lg:py-6">
            <div className="page-container">{children}</div>
          </main>
        </div>
      </div>
    </ToastProvider>
  );
}
