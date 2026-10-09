// The SSE stream for GET /api/events. MSW's high-level sse() API only types unnamed
// "message" events, but the contract needs named events (snapshot, task-updated, …), so
// this writes the text/event-stream frames directly over http.get (the msw docs'
// alternative SSE approach). It serves only docs/api/ files (ADR 0029).
import { http, HttpResponse } from 'msw';
import { snapshot } from './fixtures';

function encodeFrame(event: string, data: unknown): Uint8Array {
  return new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

export const eventsHandler = http.get('/api/events', () => {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      // First event of every connection is the snapshot; the stream then stays open.
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
