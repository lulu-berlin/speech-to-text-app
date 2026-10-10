// jsdom (and Node) have no EventSource; the eventsource polyfill provides one for tests.
// The browser (pnpm dev:mock, production) uses its native EventSource.
import { EventSource } from 'eventsource';
import '@testing-library/jest-dom/vitest';
import { server } from './server';

globalThis.EventSource = EventSource;

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
