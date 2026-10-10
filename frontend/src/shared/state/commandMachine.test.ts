import { http, HttpResponse } from 'msw';
import { createActor, waitFor } from 'xstate';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/server';
import { error as errorFixture, taskRunning } from '../api/fixtures';
import { ApiError } from '../api/types';
import { commandMachine } from './commandMachine';

describe('the command machine', () => {
  it('cannot start a command the task status does not allow, and sends no request', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    // task-transitions.json: pause is allowed from QUEUED and RUNNING only.
    const disallowed = createActor(commandMachine, {
      input: { command: 'pause', taskId: 3, status: 'COMPLETED' },
    }).start();
    disallowed.send({ type: 'START' });
    expect(disallowed.getSnapshot().value).toBe('idle');
    expect(fetchSpy).not.toHaveBeenCalled();
    disallowed.stop();

    // The guard is what stops it: the same command from an allowed status starts.
    const allowed = createActor(commandMachine, {
      input: { command: 'pause', taskId: 5, status: 'RUNNING' },
    }).start();
    allowed.send({ type: 'START' });
    expect(allowed.getSnapshot().value).toBe('sending');
    allowed.stop();

    fetchSpy.mockRestore();
  });

  it('goes sending to done with the task from the response, or to failed with the typed error', async () => {
    // Mock addition (revised, item 14): pause task 5 (RUNNING) answers with task 5
    // itself, paused — snapshot.json's lecture.m4a, which equals task.running.json.
    const done = createActor(commandMachine, {
      input: { command: 'pause', taskId: 5, status: 'RUNNING' },
    }).start();
    done.send({ type: 'START' });
    const finished = await waitFor(done, (state) => state.matches('done'), { timeout: 2000 });
    expect(finished.context.task).toEqual({ ...taskRunning, status: 'PAUSED' });
    done.stop();

    // Mock addition: the cancel handler is overridden with error.json at 409, because
    // docs/api/ has no example for 409 illegal-transition.
    server.use(
      http.post('/api/tasks/:id/cancel', () => HttpResponse.json(errorFixture, { status: 409 })),
    );
    const failed = createActor(commandMachine, {
      input: { command: 'cancel', taskId: 5, status: 'RUNNING' },
    }).start();
    failed.send({ type: 'START' });
    const errored = await waitFor(failed, (state) => state.matches('failed'), { timeout: 2000 });
    expect(errored.context.error).toBeInstanceOf(ApiError);
    expect(errored.context.error).toMatchObject({ code: 'not-found' });
    failed.stop();
  });
});
