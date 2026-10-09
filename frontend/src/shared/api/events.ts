// The SSE stream for GET /api/events. MSW's high-level sse() API only types real
// "message" events, but the contract needs named events (snapshot, task-updated, …), so
// this writes the text/event-stream frames directly over http.get (msw docs' alternative
// SSE approach). It is a factory because tests must apply the eventsource polyfill (jsdom
// has no EventSource) before the handler is used; the browser has one natively.
import { http, HttpResponse } from 'msw';
import { snapshot } from './fixtures';

function encodeFrame(event: string, data: unknown): Uint8Array {
  return new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

export function getEventsHandler() {
  return http.get('/api/events', () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        // First event of every connection is the snapshot.
        controller.enqueue(encodeFrame('snapshot', snapshot));
      },
    });
    return new HttpResponse(stream, {
      headers: {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
      },
    });
  });
}
