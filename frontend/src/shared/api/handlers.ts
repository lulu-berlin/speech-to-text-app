// MSW handlers for every item 12 endpoint and for the SSE stream. They serve only the
// contract example files in docs/api/ (ADR 0029): the frontend's mock data is that
// directory and nothing else.

import { http, HttpResponse } from 'msw';
import { eventsHandler } from './events';
import {
  taskList as taskListFixture,
  taskQueued as taskQueuedFixture,
  taskExamples,
  queue as queueFixture,
  turns as turnsFixture,
  turnsSpeakersOff as turnsSpeakersOffFixture,
  error as errorFixture,
  taskTransitions,
} from './fixtures';

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

  // Task commands: POST /api/tasks/{id}/<command> -> 200 Task. Each response is the Task
  // example whose status is the command's `to` in task-transitions.json, with the URL's id.
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
  http.post('/api/queue/pause', () => HttpResponse.json({ ...queueFixture, paused: true })),
  http.post('/api/queue/resume', () => HttpResponse.json({ ...queueFixture, paused: false })),
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

  // Events: GET /api/events, the SSE stream (events.ts).
  eventsHandler,
];
