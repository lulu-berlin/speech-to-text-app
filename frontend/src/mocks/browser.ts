// The browser MSW worker for `pnpm dev:mock` (dev only; never part of `pnpm build`).
// The worker script is served at the origin root by publicDir: 'mock' (vite.config.ts),
// so its scope covers the page, and `build.copyPublicDir: false` keeps it out of dist/.
import { setupWorker } from 'msw/browser';
import { handlers } from '../shared/api/handlers';

export const worker = setupWorker(...handlers);
