'use client';

import { createContext, useContext } from 'react';
import type { Me } from './types';

export const SessionContext = createContext<(Me & { reload: () => void }) | null>(null);

export function useSession() {
  const s = useContext(SessionContext);
  if (!s) throw new Error('useSession must be used inside the app shell');
  return s;
}

export const can = {
  moveMoney: (r: string) => r === 'admin' || r === 'bookkeeper',
  approve: (r: string) => r === 'admin',
  books: (r: string) => r === 'admin' || r === 'bookkeeper',
  admin: (r: string) => r === 'admin',
};
