// MSW handlers for every item 12 endpoint and for the SSE stream. They serve the contract
// example files in docs/api/ (ADR 0029). Where a response must reflect the request, the
// handler changes an example; each such change is marked "Mock addition" and listed in
// roadmap item 13, "Mock additions to settle with item 12".

import { http, HttpResponse } from 'msw';
import {
  heartbeat as heartbeatFixture,
  snapshot as snapshotFixture,
  taskList as taskListFixture,
  taskQueued as taskQueuedFixture,
  taskExamples,
  queue as queueFixture,
  turns as turnsFixture,
  turnsSpeakersOff as turnsSpeakersOffFixture,
  error as errorFixture,
  speakers as speakersFixture,
  taskTransitions,
  settings as settingsFixture,
} from './fixtures';

export interface EventStreamOptions {
  // SSE `retry` field: the reconnect delay the client should use after a drop.
  retryMs?: number;
  // End the stream after the events, as when the connection drops.
  end?: boolean;
  // Send a `heartbeat` this often while the stream is open, as the server does when idle
  // (ADR 0044); without it the client's watchdog would treat the stream as dropped.
  heartbeatMs?: number;
}

// A text/event-stream response that sends the given named events in order, then stays open
// unless `end` is set. MSW's high-level sse() API only types unnamed "message" events, but
// the contract needs named events (snapshot, task-updated, …), so the frames are written
// directly (the msw docs' alternative SSE approach).
export function eventStreamResponse(
  events: [name: string, data: unknown][],
  { retryMs, end = false, heartbeatMs }: EventStreamOptions = {},
): Response {
  const encoder = new TextEncoder();
  const frame = (name: string, data: unknown) =>
    encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);
  let heartbeats: ReturnType<typeof setInterval> | undefined;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      if (retryMs !== undefined) {
        controller.enqueue(encoder.encode(`retry: ${retryMs}\n\n`));
      }
      for (const [name, data] of events) {
        controller.enqueue(frame(name, data));
      }
      if (end) {
        controller.close();
      } else if (heartbeatMs !== undefined) {
        heartbeats = setInterval(
          () => controller.enqueue(frame('heartbeat', heartbeatFixture)),
          heartbeatMs,
        );
      }
    },
    // The client closed the stream (EventSource.close aborts the request).
    cancel() {
      clearInterval(heartbeats);
    },
  });
  return new HttpResponse(stream, {
    headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' },
  });
}

// The next id the mock hands an upload. It starts above every example id (the snapshot
// holds 5, 7, 3, 4), so an uploaded task never collides with a task the state already
// knows: the upload row leaves only when the stream actually announces that task, and
// the dev-mode stream sends no such event — the row stays in the upload list until a
// reload, mirroring "the backend has not confirmed the task yet" (item 18 review).
let nextTaskId = 100;

export const handlers = [
  // Upload: POST /api/tasks -> 201 Task (task.queued.json, with a fresh id). The 409
  // duplicate-audio path (error.duplicate-audio.json) is exercised by a per-test
  // override in client.test.ts.
  http.post('/api/tasks', () =>
    HttpResponse.json({ ...taskQueuedFixture, id: nextTaskId++ }, { status: 201 }),
  ),

  // List tasks: GET /api/tasks (task-list.json).
  http.get('/api/tasks', () => HttpResponse.json(taskListFixture)),

  // Single task: GET /api/tasks/{id}.
  http.get('/api/tasks/:id', ({ params }) => {
    const task = taskExamples.find((example) => example.id === Number(params.id));
    if (task === undefined) {
      return HttpResponse.json(errorFixture, { status: 404 });
    }
    return HttpResponse.json(task);
  }),

  // Task commands: POST /api/tasks/{id}/<command> -> 200 Task.
  // Mock addition (revised, item 14): the response is the requested task (from the
  // snapshot) with its status set to the command's `to` in task-transitions.json,
  // because the backend returns the real, updated task. The previous answer was the
  // unchanged example of the target status, whose filename and progress belonged to that
  // other task. For an id the snapshot does not hold, the old answer remains the fallback.
  ...Object.entries(taskTransitions.commands).map(([command, { to }]) =>
    http.post(`/api/tasks/:id/${command}`, ({ params }) => {
      const id = Number(params.id);
      const requested = snapshotFixture.tasks.find((task) => task.id === id);
      if (requested !== undefined) {
        return HttpResponse.json({ ...requested, status: to });
      }
      const example = taskExamples.find((task) => task.status === to);
      if (example === undefined) {
        throw new Error(`no docs/api/ Task example with status ${to}`);
      }
      return HttpResponse.json({ ...example, id });
    }),
  ),

  // Delete: DELETE /api/tasks/{id} -> 204 no body.
  http.delete('/api/tasks/:id', () => new HttpResponse(null, { status: 204 })),

  // Queue: GET /api/queue, POST /api/queue/pause|resume, PUT /api/queue/order.
  http.get('/api/queue', () => HttpResponse.json(queueFixture)),
  // Mock addition: queue.json with `paused` set by the command, because queue.json has
  // `paused: false` and a pause response must show the paused queue.
  http.post('/api/queue/pause', () => HttpResponse.json({ ...queueFixture, paused: true })),
  http.post('/api/queue/resume', () => HttpResponse.json({ ...queueFixture, paused: false })),
  // Mock addition: queue.json with the requested `taskIds`, because the response is the
  // queue in its new order.
  http.put('/api/queue/order', async ({ request }) => {
    const body = (await request.json()) as { taskIds: number[] };
    return HttpResponse.json({ ...queueFixture, taskIds: body.taskIds });
  }),

  // Turns: GET /api/tasks/{id}/turns (turns.json; speakers off -> turns.speakers-off.json).
  http.get('/api/tasks/:id/turns', ({ params }) => {
    const task = taskExamples.find((example) => example.id === Number(params.id));
    if (task === undefined) {
      return HttpResponse.json(errorFixture, { status: 404 });
    }
    const fixture = task.speakersMode === 'off' ? turnsSpeakersOffFixture : turnsFixture;
    return HttpResponse.json(fixture);
  }),

  // Rename a speaker: PUT /api/tasks/{id}/speakers/{speakerId} -> 200 speakers.json.
  // Mock addition (item 22): speakers.json with the requested speaker's name set to the sent
  // one (or "Speaker N" for a reset), because the response shows the stored name.
  http.put('/api/tasks/:id/speakers/:speakerId', async ({ params, request }) => {
    const speakerId = Number(params.speakerId);
    const body = (await request.json()) as { speaker: string | null };
    const name = body.speaker ?? `Speaker ${speakerId + 1}`;
    return HttpResponse.json({
      speakers: speakersFixture.speakers.map((speaker) =>
        speaker.speakerId === speakerId ? { ...speaker, speaker: name } : speaker,
      ),
    });
  }),

  // Edit a segment: PUT /api/tasks/{id}/segments/{segmentId} -> 200 turns.json with that
  // segment's text set to the sent one (item 52). Mock addition: the answer carries the edit,
  // which the view applies in place; GET /turns keeps serving the fixture, so in dev:mock a
  // reload shows the original text again.
  http.put('/api/tasks/:id/segments/:segmentId', async ({ params, request }) => {
    const segmentId = Number(params.segmentId);
    const body = (await request.json()) as { text: string };
    return HttpResponse.json({
      ...turnsFixture,
      turns: turnsFixture.turns.map((turn) => ({
        ...turn,
        segments: turn.segments.map((segment) =>
          segment.id === segmentId
            ? // The mock's fixture is whisper's text, so any different text is an edit (ADR 0061).
              { ...segment, text: body.text, edited: body.text !== segment.text }
            : segment,
        ),
      })),
    });
  }),

  // Revert a segment: POST /api/tasks/{id}/segments/{segmentId}/revert -> 200 turns.json
  // (ADR 0061). Mock addition: the fixture is whisper's text, so it answers turns.json (and
  // GET /turns keeps serving it, the mock storing no edits).
  http.post('/api/tasks/:id/segments/:segmentId/revert', () => HttpResponse.json(turnsFixture)),

  // Merge speakers: POST /api/tasks/{id}/speakers/merge -> 200 speakers.json, and reset all
  // merges: DELETE /api/tasks/{id}/speakers/merges -> 200 speakers.json. Mock addition
  // (item 22): the examples unchanged; GET /turns stays turns.json, so dev:mock shows the
  // flow but not joined turns (that needs the backend, item 21).
  http.post('/api/tasks/:id/speakers/merge', () => HttpResponse.json(speakersFixture)),
  http.delete('/api/tasks/:id/speakers/merges', () => HttpResponse.json(speakersFixture)),

  // Settings: GET /api/settings -> 200 settings.json (item 32).
  http.get('/api/settings', () => HttpResponse.json(settingsFixture)),

  // Transcript and audio are URLs, not JSON: no handlers here (the client never fetches them).

  // Events: GET /api/events, the SSE stream. The first event of every connection is the
  // snapshot; a heartbeat every 5 s keeps the client's watchdog from reconnecting (ADR 0044).
  http.get('/api/events', () =>
    eventStreamResponse([['snapshot', snapshotFixture]], { heartbeatMs: 5000 }),
  ),
];
