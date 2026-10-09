import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';

// `pnpm dev:mock` runs Vite in "mock" mode. The single entry (index.html) then loads the
// MSW entry (`src/mock-main.tsx`) instead of `src/main.tsx`, so the mock app is served at
// the origin root and the real routes work (`/queue`, `/history/…`). A production build
// never sees this: `pnpm build` runs in the default mode.
function mockEntry(): Plugin {
  return {
    name: 'stt-mock-entry',
    apply: 'serve',
    transformIndexHtml(html, context) {
      if (context.server?.config.mode !== 'mock') {
        return html;
      }
      return html.replace('/src/main.tsx', '/src/mock-main.tsx');
    },
  };
}

export default defineConfig({
  plugins: [react(), mockEntry()],
  // The MSW worker for `pnpm dev:mock`: served at the origin root (/mockServiceWorker.js),
  // so its scope covers the page; `build.copyPublicDir: false` keeps it out of dist/.
  publicDir: 'mock',
  build: {
    copyPublicDir: false,
  },
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
