// MSW handlers for every item 12 endpoint and for the SSE stream. They serve only the
// contract example files in docs/api/ (ADR 0029): the frontend's mock data is that
// directory and nothing else.

import { http, HttpResponse } from 'msw';
import {
  taskList as taskListFixture,
  taskQueued as taskQueuedFixture,
  taskRunning as taskRunningFixture,
  taskCompleted as taskCompletedFixture,
  taskFailed as taskFailedFixture,
  taskPaused as taskPausedFixture,
  taskCanceled as taskCanceledFixture,
  queue as queueFixture,
  turns as turnsFixture,
  turnsSpeakersOff as turnsSpeakersOffFixture,
  errorNotFound as errorFixture,
  taskTransitions,
} from './fixtures';

import type { Task } from '../../shared/api/types';

const TRANSITION_RESPONSE: Record<string, Task> = {
  RUNNING: taskRunningFixture as Task,
  PAUSED: taskPausedFixture as Task,
  QUEUED: taskQueuedFixture as Task,
  COMPLETED: taskCompletedFixture as Task,
  CANCELED: taskCanceledFixture as Task,
  FAILED: taskFailedFixture as Task,
};

function applyId(task: Task, id: number): Task {
  return { ...task, id };
}

// The 201/200 task responses of the command endpoints are the example whose status
// matches the transition table's requested `to` status, with the URL's id.
function statusFixtureByTo(to: string): Task {
  const task = TRANSITION_RESPONSE[to];
  if (task === undefined) {
    throw new Error(`no fixture for requested transition status ${to}`);
  }
  return task;
}

function taskById(id: number): Task | undefined {
  return [
    taskQueuedFixture,
    taskRunningFixture,
    taskCompletedFixture,
    taskFailedFixture,
    taskPausedFixture,
    taskCanceledFixture,
  ].find((task) => (task as Task).id === id) as Task | undefined;
}

export const handlers = [
  // List tasks: GET /api/tasks (task-list.json).
  http.get('/api/tasks', () => HttpResponse.json(taskListFixture)),

  // Single task: GET /api/tasks/{id}.
  http.get('/api/tasks/:id', ({ params }) => {
    const id = Number(params.id);
    const task = taskById(id);
    if (task === undefined) {
      return HttpResponse.json(errorFixture, { status: 404 });
    }
    return HttpResponse.json(applyId(task, id));
  }),

  // Task commands: POST /api/tasks/{id}/pause|resume|cancel|retry -> 200 Task.
  http.post('/api/tasks/:id/pause', ({ params }) => {
    const id = Number(params.id);
    return HttpResponse.json(applyId(statusFixtureByTo('PAUSED'), id), { status: 200 });
  }),
  http.post('/api/tasks/:id/resume', ({ params }) => {
    const id = Number(params.id);
    return HttpResponse.json(applyId(statusFixtureByTo('QUEUED'), id), { status: 200 });
  }),
  http.post('/api/tasks/:id/cancel', ({ params }) => {
    const id = Number(params.id);
    return HttpResponse.json(applyId(statusFixtureByTo('CANCELED'), id), { status: 200 });
  }),
  http.post('/api/tasks/:id/retry', ({ params }) => {
    const id = Number(params.id);
    return HttpResponse.json(applyId(statusFixtureByTo('QUEUED'), id), { status: 200 });
  }),

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
    const id = Number(params.id);
    const task = taskById(id);
    if (task === undefined) {
      return HttpResponse.json(errorFixture, { status: 404 });
    }
    const fixture = task.speakersMode === 'off' ? turnsSpeakersOffFixture : turnsFixture;
    return HttpResponse.json(fixture);
  }),

  // Transcript and audio are URLs, not JSON: no handlers here (the client never fetches them).
  // The SSE stream lives in events.ts (getEventsHandler), constructed only on connect.
];

export const getTaskTransitions = taskTransitions;
