// jsdom and Node have no EventSource; the eventsource polyfill provides the real one,
// which MSW's sse() handler intercepts in Node and the service worker intercepts in
// the browser, so tests and pnpm dev:mock share one streaming path.
//
// This is the platform-specific case that static imports cannot cover: `eventsource`
// only exists outside browsers, and globalThis.EventSource must be assigned before the
// handlers module evaluates (MSW's sse() handler reads EventSource at module load) —
// but ESM hoisting makes any static import of handlers run before this statement.
import { EventSource } from 'eventsource';
import '@testing-library/jest-dom/vitest';

globalThis.EventSource = EventSource;

import { setupServer } from 'msw/node';
import { handlers } from './../shared/api/handlers';

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
