// The browser MSW worker for `pnpm dev:mock` (dev only; never part of `pnpm build`).
// The worker script lives in mock/ (outside public/), so Vite never copies it into dist.
import { setupWorker } from 'msw/browser';
import { handlers } from '../shared/api/handlers';

export const WORKER_URL = '/mock/mockServiceWorker.js';

export const worker = setupWorker(...handlers);
