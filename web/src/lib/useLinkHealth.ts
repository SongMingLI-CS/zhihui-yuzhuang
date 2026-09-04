'use client';

import { useEffect, useState } from 'react';
import { checkAi, checkBackend, checkGateway } from './http';

export type LinkState = 'checking' | 'ok' | 'down';

export interface LinkHealth {
  gateway: LinkState;
  backend: LinkState;
  ai: LinkState;
}

const INITIAL: LinkHealth = { gateway: 'checking', backend: 'checking', ai: 'checking' };
const POLL_MS = 20_000;

/** 轮询网关 / backend / ai-service 三项链路健康状态 */
export function useLinkHealth(): LinkHealth {
  const [health, setHealth] = useState<LinkHealth>(INITIAL);

  useEffect(() => {
    let active = true;

    const run = async () => {
      const [gateway, backend, ai] = await Promise.all([
        checkGateway(),
        checkBackend(),
        checkAi(),
      ]);
      if (!active) return;
      setHealth({
        gateway: gateway ? 'ok' : 'down',
        backend: backend ? 'ok' : 'down',
        ai: ai ? 'ok' : 'down',
      });
    };

    void run();
    const timer = window.setInterval(() => {
      void run();
    }, POLL_MS);

    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  return health;
}
