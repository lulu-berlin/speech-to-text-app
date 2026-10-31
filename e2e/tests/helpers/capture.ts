import type { Page, Request, Response } from '@playwright/test';

// Records what the browser actually receives from the real backend, for the contract
// drift check (roadmap item 21, item 14): every /api JSON body and every SSE event.
// Nothing is intercepted — the app talks to the same-origin /api normally; these
// listeners only observe.

export type SseEvent = { name: string; data: unknown };

declare global {
  // The SSE recorder the init script installs in the page, which `capturedSse` reads back.
  var __sttE2eSseEvents: SseEvent[] | undefined;
}

export type HttpCapture = { url: string; method: string; status: number; json: unknown }[];

export type Captured = {
  http: HttpCapture;
  sse: SseEvent[];
};

// Installs the SSE recorder before the app's EventSource opens, so every event that
// reaches the browser is captured. Must run before the page navigates to the app.
export async function installSseCapture(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const realEventSource = globalThis.EventSource;
    if (globalThis.__sttE2eSseEvents !== undefined) return;
    globalThis.__sttE2eSseEvents = [];
    const events = globalThis.__sttE2eSseEvents;

    // The app's SSE events are named; capture those plus any unnamed fallback.
    const NAMES = [
      'snapshot',
      'task-updated',
      'task-progress',
      'queue-updated',
      'task-deleted',
      'speakers-renamed',
      'speakers-merged',
      'speakers-reassigned',
      'heartbeat',
    ];
    globalThis.EventSource = new Proxy(realEventSource, {
      construct(target, args) {
        const source = new target(args[0]);
        const record = (name: string, raw: string) => {
          let data: unknown = raw;
          try {
            data = JSON.parse(raw);
          } catch {
            // unparseable payloads are contract drift by themselves; record the raw text
          }
          events.push({ name, data });
        };
        for (const name of NAMES) {
          source.addEventListener(name, (event: MessageEvent) => record(name, event.data));
        }
        source.addEventListener('message', (event: MessageEvent) => {
          // unnamed events fall back to `message`; a named event is dispatched twice only
          // if the server sent it without a name, which is itself drift.
          record('event', event.data);
        });
        return source;
      },
    });
  });
}

export function capturedSse(page: Page): Promise<SseEvent[]> {
  return page.evaluate(() => [...(globalThis.__sttE2eSseEvents ?? [])]);
}

// Attaches the HTTP response recorder. Must run before the responses to check arrive.
// The browser's request method is recorded for correlation (the Response object has no
// method), so the upload's POST /api/tasks (a task) is not mistaken for GET /api/tasks
// (the task list).
export function installHttpCapture(page: Page, captured: HttpCapture) {
  const methods = new Map<string, string>();
  page.on('request', (request: Request) => {
    const url = request.url();
    if (!url.includes('/api/') || url.includes('/api/events')) return;
    methods.set(url, request.method());
  });
  page.on('response', async (response: Response) => {
    const url = response.url();
    if (!url.includes('/api/') || url.includes('/api/events')) return;
    if (url.endsWith('/audio') || url.includes('/transcript?')) return; // binary, not JSON
    try {
      const body = await response.body();
      const json = body.length === 0 ? null : JSON.parse(new TextDecoder().decode(body));
      captured.push({ url, method: methods.get(url) ?? 'GET', status: response.status(), json });
    } catch {
      // a 204 (DELETE) or another bodyless response — nothing to shape-check
    }
  });
}
