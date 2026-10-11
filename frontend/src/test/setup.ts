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

// jsdom has no pointer capture. Radix's slider captures the pointer on the drag target at
// pointer-down and releases it at pointer-up; the capture gates each pointer-move and the
// release commit, so the drag tests need it tracked per element, like the browser's
// per-(element, pointerId) capture.
const capturedPointers = new WeakSet<Element>();
Element.prototype.setPointerCapture = function (this: Element): void {
  capturedPointers.add(this);
};
Element.prototype.hasPointerCapture = function (this: Element): boolean {
  return capturedPointers.has(this);
};
Element.prototype.releasePointerCapture = function (this: Element): void {
  capturedPointers.delete(this);
};

// jsdom has no scrollIntoView; the transcript's follow mode calls it. A no-op is enough —
// the tests spy on it to assert the call, and browsers keep the real behavior.
if (typeof Element.prototype.scrollIntoView !== 'function') {
  Element.prototype.scrollIntoView = () => {};
}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
