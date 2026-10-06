'use client';

import { useCallback, useEffect, useState } from 'react';

const TOKEN_KEY = 'lb_token';

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(t: string | null) {
  try {
    if (t) localStorage.setItem(TOKEN_KEY, t);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage unavailable */
  }
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string) {
    super(message);
  }
}

/** Calls the LedgerBank API through the Next.js /api proxy. Amounts are integer cents. */
export async function api<T = unknown>(path: string, opts: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<T> {
  const token = getToken();
  const res = await fetch(`/api${path}`, {
    method: opts.method ?? (opts.body !== undefined ? 'POST' : 'GET'),
    headers: {
      ...(opts.body !== undefined ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...opts.headers,
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  if (res.status === 401 && typeof window !== 'undefined' && !path.startsWith('/auth/login') && !path.startsWith('/public')) {
    setToken(null);
    window.location.href = '/login';
  }
  const ct = res.headers.get('content-type') ?? '';
  const data = ct.includes('json') ? await res.json() : await res.text();
  if (!res.ok) throw new ApiError(res.status, (data as { message?: string })?.message ?? res.statusText, (data as { error?: string })?.error);
  return data as T;
}

export const post = <T = unknown>(path: string, body: unknown = {}) => api<T>(path, { method: 'POST', body });
export const patch = <T = unknown>(path: string, body: unknown) => api<T>(path, { method: 'PATCH', body });
export const del = <T = unknown>(path: string) => api<T>(path, { method: 'DELETE' });

/** Downloads a CSV (or any file) from the API with auth. */
export async function download(path: string, filename: string) {
  const res = await fetch(`/api${path}`, { headers: { authorization: `Bearer ${getToken()}` } });
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/** Minimal data hook: fetches on mount and whenever `path` changes; `reload()` refetches. Pass null to skip. */
export function useApi<T>(path: string | null) {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(!!path);
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    if (!path) return;
    let live = true;
    setLoading(true);
    api<T>(path)
      .then((d) => { if (live) { setData(d); setError(null); } })
      .catch((e: Error) => { if (live) setError(e.message); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [path, nonce]);
  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { data, error, loading, reload, setData };
}
