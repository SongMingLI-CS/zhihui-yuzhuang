import type { UserInfo } from './types';

/**
 * 认证态轻量外部存储（与 tenant.ts 同构）：
 * 持久化当前登录用户信息，并对外提供订阅，供顶栏用户区与登录页共享。
 * 令牌本身仍由 http.ts 的 setAccessToken 读写 localStorage（key: yuzhuang.access_token）。
 */

const USER_KEY = 'yuzhuang.user';

export function getStoredUser(): UserInfo | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as UserInfo) : null;
  } catch {
    return null;
  }
}

export function persistUser(user: UserInfo | null): void {
  if (typeof window === 'undefined') return;
  if (user) window.localStorage.setItem(USER_KEY, JSON.stringify(user));
  else window.localStorage.removeItem(USER_KEY);
}

let current: UserInfo | null = null;
const listeners = new Set<(u: UserInfo | null) => void>();

export function getCurrentUser(): UserInfo | null {
  return current;
}

export function setCurrentUser(user: UserInfo | null): void {
  current = user;
  listeners.forEach((fn) => fn(user));
}

export function subscribeAuth(fn: (u: UserInfo | null) => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
