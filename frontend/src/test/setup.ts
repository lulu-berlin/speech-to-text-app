// jsdom (and Node) have no EventSource; the eventsource polyfill provides one for tests.
// The browser (pnpm dev:mock, production) uses its native EventSource.
import { EventSource } from 'eventsource';
import '@testing-library/jest-dom/vitest';
import { server } from './server';

globalThis.EventSource = EventSource;

// jsdom implements none of the <dialog> methods (showModal/close/show are undefined), so
// tests shim the parts the app uses; real browsers provide them.
const dialogPrototype = globalThis.HTMLDialogElement?.prototype as
  (HTMLDialogElement & { showModal?: () => void; show?: () => void }) | undefined;

if (dialogPrototype !== undefined && typeof dialogPrototype.showModal !== 'function') {
  dialogPrototype.showModal = function showModal(this: HTMLDialogElement) {
    this.open = true;
  };
  dialogPrototype.show = function show(this: HTMLDialogElement) {
    this.open = true;
  };
  dialogPrototype.close = function close(this: HTMLDialogElement, returnValue?: string) {
    if (returnValue !== undefined) {
      this.returnValue = returnValue;
    }
    this.open = false;
    this.dispatchEvent(new Event('close'));
  };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
