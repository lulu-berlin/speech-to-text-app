import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    fs: {
      // docs/api/ is the single mock source and lies outside frontend/ (ADR 0029).
      allow: ['..'],
    },
    proxy: {
      // Backend dev server (convention: dev proxies /api, prod serves dist/ same-origin)
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
