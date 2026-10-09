// MSW handlers for every item 12 endpoint and for the SSE stream. They serve the contract
// example files in docs/api/ (ADR 0029). Where a response must reflect the request, the
// handler changes an example; each such change is marked "Mock addition" and listed in
// roadmap item 13, "Mock additions to settle with item 12".

import { http, HttpResponse } from 'msw';
import {
  snapshot as snapshotFixture,
  taskList as taskListFixture,
  taskQueued as taskQueuedFixture,
  taskExamples,
  queue as queueFixture,
  turns as turnsFixture,
  turnsSpeakersOff as turnsSpeakersOffFixture,
  error as errorFixture,
  taskTransitions,
} from './fixtures';

// One text/event-stream frame. MSW's high-level sse() API only types unnamed "message"
// events, but the contract needs named events (snapshot, task-updated, …), so the stream
// is written directly (the msw docs' alternative SSE approach).
function encodeFrame(event: string, data: unknown): Uint8Array {
  return new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

export const handlers = [
  // Upload: POST /api/tasks -> 201 Task (task.queued.json). The 409 duplicate-audio path
  // (error.duplicate-audio.json) is exercised by a per-test override in client.test.ts.
  http.post('/api/tasks', () => HttpResponse.json(taskQueuedFixture, { status: 201 })),

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
  // Mock addition: the response is the Task example whose status is the command's `to` in
  // task-transitions.json, with the URL's id, because the examples hold one Task per status,
  // not one per (task, command) pair.
  ...Object.entries(taskTransitions.commands).map(([command, { to }]) =>
    http.post(`/api/tasks/:id/${command}`, ({ params }) => {
      const example = taskExamples.find((task) => task.status === to);
      if (example === undefined) {
        throw new Error(`no docs/api/ Task example with status ${to}`);
      }
      return HttpResponse.json({ ...example, id: Number(params.id) });
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

  // Transcript and audio are URLs, not JSON: no handlers here (the client never fetches them).

  // Events: GET /api/events, the SSE stream. The first event of every connection is the
  // snapshot; the stream then stays open.
  http.get('/api/events', () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encodeFrame('snapshot', snapshotFixture));
      },
    });
    return new HttpResponse(stream, {
      headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' },
    });
  }),
];
