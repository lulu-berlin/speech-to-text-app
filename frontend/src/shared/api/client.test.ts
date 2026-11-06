import { http, HttpResponse } from 'msw/http';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/server';
import { ApiError } from './types';
import { getTask, listTasks, uploadTask } from './client';
import { errorDuplicateAudio, taskList, taskQueued } from './fixtures';

describe('the typed API client', () => {
  it('returns typed tasks for GET /api/tasks (from docs/api/task-list.json)', async () => {
    const result = await listTasks();

    // The contract: GET /api/tasks returns { tasks: [Task] } with Task's status union.
    expect(Array.isArray(result.tasks)).toBe(true);
    expect(result.tasks).toHaveLength(taskList.tasks.length);

    // Ids and statuses come from the contract example, not from the client's output.
    const expected = taskList.tasks.map((task) => [task.id, task.status]);
    expect(result.tasks.map((task) => [task.id, task.status])).toEqual(expected);

    // Task fields mirror the item 12 contract shape.
    const first = result.tasks[0]!;
    expect(typeof first.id).toBe('number');
    expect(typeof first.filename).toBe('string');
    expect(['QUEUED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELED']).toContain(
      first.status,
    );
    expect(typeof first.durationMs === 'number' || first.durationMs === null).toBe(true);
  });

  it('turns an error response into a typed error with code and message', async () => {
    const request = getTask(999);
    await expect(request).rejects.toBeInstanceOf(ApiError);
    await expect(request).rejects.toMatchObject({
      code: 'not-found',
      message: 'Task 42 does not exist',
    });
  });

  it('carries the taskId on a duplicate-audio 409', async () => {
    server.use(
      http.post('/api/tasks', () => HttpResponse.json(errorDuplicateAudio, { status: 409 })),
    );

    const request = uploadTask(new File(['x'], 'memo.wav'), {
      language: 'auto',
      speakers: 'auto',
      maxSpeakers: 8,
    });
    await expect(request).rejects.toBeInstanceOf(ApiError);
    await expect(request).rejects.toMatchObject({ code: 'duplicate-audio', taskId: 3 });
  });

  it('aborts a pending upload once and never touches a settled one', async () => {
    // jsdom's XHR cannot be held mid-request, so a fake records `abort()` and fires onabort.
    class FakeXhr {
      static instances: FakeXhr[] = [];
      upload = { onprogress: null as ((event: ProgressEvent) => void) | null };
      responseType = '';
      status = 0;
      statusText = '';
      response: unknown = null;
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      onabort: (() => void) | null = null;
      aborted = 0;
      constructor() {
        FakeXhr.instances.push(this);
      }
      open(): void {}
      send(): void {}
      abort(): void {
        this.aborted += 1;
        this.onabort?.();
      }
    }
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
    try {
      const options = { language: 'auto', speakers: 'auto', maxSpeakers: 8 };

      // A pending request is aborted once and rejects with the app's message.
      const controller = new AbortController();
      const pending = uploadTask(new File(['a'], 'a.mp3'), options, undefined, controller.signal);
      const first = FakeXhr.instances[0]!;
      controller.abort();
      await expect(pending).rejects.toMatchObject({
        message: "Not saved: can't reach the app's service. Reconnecting…",
      });
      expect(first.aborted).toBe(1);

      // A settled request is not aborted by a later teardown of the same controller.
      const settledController = new AbortController();
      const settled = uploadTask(
        new File(['b'], 'b.mp3'),
        options,
        undefined,
        settledController.signal,
      );
      const second = FakeXhr.instances[1]!;
      second.status = 201;
      second.response = taskQueued;
      second.onload?.();
      await expect(settled).resolves.toMatchObject({ id: taskQueued.id });
      settledController.abort();
      expect(second.aborted).toBe(0);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
