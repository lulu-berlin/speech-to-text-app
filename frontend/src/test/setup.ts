// jsdom (and Node) have no EventSource; the eventsource polyfill provides one for tests.
// The browser (pnpm dev:mock, production) uses its native EventSource.
import { EventSource } from 'eventsource';
import '@testing-library/jest-dom/vitest';
import { server } from './server';

globalThis.EventSource = EventSource;

// jsdom has no ResizeObserver; Radix primitives that measure (Slider) need one. The stub
// only has to exist — tests never exercise actual resize observation.
class ResizeObserverStub implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
