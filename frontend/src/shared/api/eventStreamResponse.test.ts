// The mock's /api/events stream contract (ADR 0029): the frames eventStreamResponse
// writes, which the app's EventSource reads.
import { http } from 'msw/http';
import { describe, expect, it, vi } from 'vitest';
import {
  heartbeat as heartbeatFixture,
  snapshot as snapshotFixture,
  taskRunning,
} from './fixtures';
import { eventStreamResponse } from './handlers';
import { server } from '../../test/server';

describe('the mocked /api/events stream', () => {
  it('writes retry, then the named events in order, and ends the stream early', async () => {
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse(
          [
            ['snapshot', snapshotFixture],
            ['task-updated', taskRunning],
          ],
          { retryMs: 5000, end: true },
        ),
      ),
    );

    const response = await fetch('/api/events');

    expect(response.headers.get('content-type')).toBe('text/event-stream');
    // The wire format: the retry field first, then one frame per event, in the given order.
    expect(await response.text()).toBe(
      [
        'retry: 5000\n\n',
        `event: snapshot\ndata: ${JSON.stringify(snapshotFixture)}\n\n`,
        `event: task-updated\ndata: ${JSON.stringify(taskRunning)}\n\n`,
      ].join(''),
    );
  });

  it('sends a heartbeat frame every heartbeatMs while the stream stays open', async () => {
    vi.useFakeTimers();
    try {
      const response = eventStreamResponse([['snapshot', snapshotFixture]], { heartbeatMs: 40 });
      const body = response.body;
      if (body === null) {
        throw new Error('the stream has no body');
      }
      const reader = body.getReader();
      const decoder = new TextDecoder();

      expect(decoder.decode((await reader.read()).value)).toBe(
        `event: snapshot\ndata: ${JSON.stringify(snapshotFixture)}\n\n`,
      );

      await vi.advanceTimersByTimeAsync(40);

      // ADR 0044: the server's idle heartbeat; the client's watchdog counts it as an event.
      expect(decoder.decode((await reader.read()).value)).toBe(
        `event: heartbeat\ndata: ${JSON.stringify(heartbeatFixture)}\n\n`,
      );

      // Closing the client's side stops the interval: no frame arrives after the cancel.
      await reader.cancel();
      await vi.advanceTimersByTimeAsync(80);
      expect(await reader.read()).toEqual({ done: true, value: undefined });
    } finally {
      vi.useRealTimers();
    }
  });
});
