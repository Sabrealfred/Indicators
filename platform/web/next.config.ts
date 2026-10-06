import type { NextConfig } from 'next';

const API_URL = process.env.API_URL ?? 'http://localhost:4000';

const config: NextConfig = {
  // The browser talks to /api/*; Next proxies it to the API so there is no CORS or token leakage across origins.
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${API_URL}/:path*` }];
  },
};

export default config;
