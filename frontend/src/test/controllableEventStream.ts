import { http, HttpResponse } from 'msw';
import { snapshot as snapshotFixture } from '../shared/api/fixtures';
import { server } from './server';

// A live /api/events stream for one test: it opens with the snapshot
// (docs/api/snapshot.json), then sends each event the test pushes, so the test can check
// one state before the next event arrives.
export function controllableEventStream() {
  const encoder = new TextEncoder();
  let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
  const events = {
    send(name: string, data: unknown) {
      if (controller === undefined) {
        throw new Error('the event stream is not connected yet');
      }
      controller.enqueue(encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`));
    },
  };
  server.use(
    http.get(
      '/api/events',
      () =>
        new HttpResponse(
          new ReadableStream<Uint8Array>({
            start(streamController) {
              controller = streamController;
              events.send('snapshot', snapshotFixture);
            },
          }),
          { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' } },
        ),
    ),
  );
  return events;
}
