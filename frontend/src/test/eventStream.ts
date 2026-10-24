import { http, HttpResponse } from 'msw';
import { snapshot as snapshotFixture } from '../shared/api/fixtures';
import { server } from './server';

// A live /api/events stream for one test: it opens with the snapshot (by default
// docs/api/snapshot.json), then sends each event the test pushes, so the test can check
// one state before the next event arrives.
export function controllableEventStream(snapshot: unknown = snapshotFixture) {
  const encoder = new TextEncoder();
  let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
  const events = {
    send(name: string, data: unknown) {
      events.sendTogether([[name, data]]);
    },
    // Several events in one chunk, as one network read can deliver them: EventSource then
    // dispatches them all before the page renders again.
    sendTogether(frames: [name: string, data: unknown][]) {
      if (controller === undefined) {
        throw new Error('the event stream is not connected yet');
      }
      const chunk = frames.map(
        ([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`,
      );
      controller.enqueue(encoder.encode(chunk.join('')));
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
              events.send('snapshot', snapshot);
            },
          }),
          { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' } },
        ),
    ),
  );
  return events;
}
