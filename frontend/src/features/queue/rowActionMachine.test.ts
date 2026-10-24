import { http, HttpResponse } from 'msw';
import { createActor, waitFor } from 'xstate';
import { describe, expect, it } from 'vitest';
import {
  error as errorFixture,
  errorInvalidLanguage,
  taskQueued,
  taskRunning,
} from '../../shared/api/fixtures';
import type { Task } from '../../shared/api/types';
import { server } from '../../test/server';
import { rowActionMachine } from './rowActionMachine';
import type { RowAction } from './rowActionMachine';

// Answers POST /api/tasks/:id/<command> with answer() only once release() is called, so a
// second command can arrive while this one is in flight. arrived resolves when the request
// reaches the handler; each call is recorded in calls as "<command> <id>".
function heldCommand(command: string, answer: () => Response, calls: string[] = []) {
  const arrived = Promise.withResolvers<void>();
  const released = Promise.withResolvers<void>();
  server.use(
    http.post(`/api/tasks/:id/${command}`, async ({ params }) => {
      calls.push(`${command} ${String(params.id)}`);
      arrived.resolve();
      await released.promise;
      return answer();
    }),
  );
  return { arrived: arrived.promise, release: () => released.resolve() };
}

// Answers POST /api/tasks/:id/<command> with answer() at once, recording each call in calls
// as "<command> <id>".
function answeredCommand(command: string, answer: () => Response, calls: string[]) {
  server.use(
    http.post(`/api/tasks/:id/${command}`, ({ params }) => {
      calls.push(`${command} ${String(params.id)}`);
      return answer();
    }),
  );
}

// The ACTIVATE a row button sends: the action, the task status it was clicked from, and
// whether the row asks for confirmation first.
function activate(action: RowAction, status: Task['status'], confirm = false) {
  return { type: 'ACTIVATE' as const, action, status, confirm };
}

describe('the row action machine', () => {
  it('runs a command that arrives while another is in flight once the first one settles', async () => {
    // Item 81: the row's buttons are disabled while a command runs, but an ACTIVATE can
    // still arrive (dispatched before they re-render).
    const calls: string[] = [];
    const pause = heldCommand(
      'pause',
      () => HttpResponse.json({ ...taskRunning, status: 'PAUSED' }),
      calls,
    );
    answeredCommand('resume', () => HttpResponse.json(taskRunning), calls);

    const row = createActor(rowActionMachine, { input: { taskId: 5, status: 'RUNNING' } }).start();
    row.send(activate('pause', 'RUNNING'));
    await pause.arrived;
    // The row's SSE status turned PAUSED, which is what offers Resume.
    row.send({ type: 'STATUS', status: 'PAUSED' });
    row.send(activate('resume', 'PAUSED'));

    // Nothing more is sent while the pause is in flight.
    expect(calls).toEqual(['pause 5']);

    // Once the pause settles, the kept resume runs; its response is the resumed task.
    pause.release();
    const resumed = await waitFor(
      row,
      (state) => state.matches({ ready: 'done' }) && state.context.task?.status === 'RUNNING',
      { timeout: 2000 },
    );
    expect(resumed.context.task).toEqual(taskRunning);
    expect(calls).toEqual(['pause 5', 'resume 5']);
    row.stop();
  });

  it("keeps a failed command's error while the kept command runs, cleared by its success or replaced by its failure", async () => {
    // Item 81. 409 illegal-transition has no docs/api example, so error.json stands in
    // for the failed pause.
    const pause = heldCommand('pause', () => HttpResponse.json(errorFixture, { status: 409 }));
    const resume = heldCommand('resume', () => HttpResponse.json(taskRunning));

    const cleared = createActor(rowActionMachine, {
      input: { taskId: 5, status: 'RUNNING' },
    }).start();
    cleared.send(activate('pause', 'RUNNING'));
    await pause.arrived;
    cleared.send({ type: 'STATUS', status: 'PAUSED' });
    cleared.send(activate('resume', 'PAUSED'));
    pause.release();

    // The pause failed and the kept resume runs, with the pause's error still on the row.
    await resume.arrived;
    const replaying = cleared.getSnapshot();
    expect(replaying.matches('running')).toBe(true);
    expect(replaying.context.action).toBe('resume');
    expect(replaying.context.error).toMatchObject({ code: 'not-found' });

    // The resume succeeds: the error is gone.
    resume.release();
    const resumed = await waitFor(cleared, (state) => state.matches({ ready: 'done' }), {
      timeout: 2000,
    });
    expect(resumed.context.error).toBeNull();
    expect(resumed.context.task).toEqual(taskRunning);
    cleared.stop();

    // The same race where the kept resume fails too: its error replaces the pause's.
    // error.invalid-language.json stands in for a second, distinguishable failure.
    const secondPause = heldCommand('pause', () =>
      HttpResponse.json(errorFixture, { status: 409 }),
    );
    server.use(
      http.post('/api/tasks/:id/resume', () =>
        HttpResponse.json(errorInvalidLanguage, { status: 400 }),
      ),
    );

    const replaced = createActor(rowActionMachine, {
      input: { taskId: 5, status: 'RUNNING' },
    }).start();
    replaced.send(activate('pause', 'RUNNING'));
    await secondPause.arrived;
    replaced.send({ type: 'STATUS', status: 'PAUSED' });
    replaced.send(activate('resume', 'PAUSED'));
    secondPause.release();

    const failed = await waitFor(
      replaced,
      (state) => state.matches({ ready: 'failed' }) && state.context.action === 'resume',
      { timeout: 2000 },
    );
    expect(failed.context.error).toMatchObject({ code: 'invalid-language' });
    replaced.stop();
  });

  it("drops a kept command the transition table no longer allows for the task's live status: no request, no error", async () => {
    // Item 81. A second pause clicked before the row re-rendered: the first one's
    // response is the live task, PAUSED, and task-transitions.json allows pause only from
    // QUEUED and RUNNING.
    const calls: string[] = [];
    const pause = heldCommand(
      'pause',
      () => HttpResponse.json({ ...taskRunning, status: 'PAUSED' }),
      calls,
    );

    const twice = createActor(rowActionMachine, {
      input: { taskId: 5, status: 'RUNNING' },
    }).start();
    twice.send(activate('pause', 'RUNNING'));
    await pause.arrived;
    twice.send(activate('pause', 'RUNNING'));
    pause.release();

    const paused = await waitFor(twice, (state) => state.matches({ ready: 'done' }), {
      timeout: 2000,
    });
    expect(paused.context.task).toEqual({ ...taskRunning, status: 'PAUSED' });
    expect(paused.context.error).toBeNull();
    expect(calls).toEqual(['pause 5']);
    twice.stop();

    // The live status the row reports while the command runs counts too: a resume kept
    // from PAUSED, then the task completes (the row reports COMPLETED) and the pause is
    // refused. The resume is dropped; only the pause's own error shows. 409
    // illegal-transition has no docs/api example, so error.json stands in.
    const refusedCalls: string[] = [];
    const refused = heldCommand(
      'pause',
      () => HttpResponse.json(errorFixture, { status: 409 }),
      refusedCalls,
    );
    answeredCommand('resume', () => HttpResponse.json(taskRunning), refusedCalls);

    const completed = createActor(rowActionMachine, {
      input: { taskId: 5, status: 'RUNNING' },
    }).start();
    completed.send(activate('pause', 'RUNNING'));
    await refused.arrived;
    completed.send({ type: 'STATUS', status: 'PAUSED' });
    completed.send(activate('resume', 'PAUSED'));
    completed.send({ type: 'STATUS', status: 'COMPLETED' });
    refused.release();

    const failed = await waitFor(completed, (state) => state.matches({ ready: 'failed' }), {
      timeout: 2000,
    });
    expect(failed.context.action).toBe('pause');
    expect(failed.context.error).toMatchObject({ code: 'not-found' });
    expect(refusedCalls).toEqual(['pause 5']);
    completed.stop();
  });

  it('asks for confirmation before a kept cancel runs', async () => {
    // Item 81: a cancel of a started task asks first (the row sets confirm); kept while a
    // pause runs, it still does once the pause settles.
    const calls: string[] = [];
    const pause = heldCommand(
      'pause',
      () => HttpResponse.json({ ...taskRunning, status: 'PAUSED' }),
      calls,
    );
    answeredCommand(
      'cancel',
      () => HttpResponse.json({ ...taskRunning, status: 'CANCELED' }),
      calls,
    );

    const row = createActor(rowActionMachine, { input: { taskId: 5, status: 'RUNNING' } }).start();
    row.send(activate('pause', 'RUNNING'));
    await pause.arrived;
    row.send(activate('cancel', 'RUNNING', true));
    pause.release();

    // The pause settled; the kept cancel waits for the user's answer, no request yet.
    const asking = await waitFor(row, (state) => state.matches('confirming'), { timeout: 2000 });
    expect(asking.context.action).toBe('cancel');
    expect(calls).toEqual(['pause 5']);

    row.send({ type: 'CONFIRM' });
    const canceled = await waitFor(
      row,
      (state) => state.matches({ ready: 'done' }) && state.context.task?.status === 'CANCELED',
      { timeout: 2000 },
    );
    expect(canceled.context.action).toBe('cancel');
    expect(calls).toEqual(['pause 5', 'cancel 5']);
    row.stop();
  });

  it('runs only the last of several commands that arrive while one is in flight', async () => {
    // Item 81. memo.wav (task 7) is QUEUED and never started, so its cancel needs no
    // confirmation; resume and cancel are both allowed from PAUSED, so either could run.
    const calls: string[] = [];
    const pause = heldCommand(
      'pause',
      () => HttpResponse.json({ ...taskQueued, status: 'PAUSED' }),
      calls,
    );
    answeredCommand('resume', () => HttpResponse.json(taskQueued), calls);
    answeredCommand(
      'cancel',
      () => HttpResponse.json({ ...taskQueued, status: 'CANCELED' }),
      calls,
    );

    const row = createActor(rowActionMachine, { input: { taskId: 7, status: 'QUEUED' } }).start();
    row.send(activate('pause', 'QUEUED'));
    await pause.arrived;
    row.send({ type: 'STATUS', status: 'PAUSED' });
    row.send(activate('resume', 'PAUSED'));
    row.send(activate('cancel', 'PAUSED'));
    pause.release();

    const canceled = await waitFor(
      row,
      (state) => state.matches({ ready: 'done' }) && state.context.task?.status === 'CANCELED',
      { timeout: 2000 },
    );
    expect(canceled.context.action).toBe('cancel');
    expect(calls).toEqual(['pause 7', 'cancel 7']);
    row.stop();
  });

  it("leaves the earlier failure's error on the row when a replayed cancel's confirmation is dismissed, until the next action", async () => {
    // Item 81 (discovered). The pause fails while a cancel of the started, paused task is
    // kept; the cancel asks first and the user declines. 409 illegal-transition has no
    // docs/api example, so error.json stands in.
    const calls: string[] = [];
    const pause = heldCommand(
      'pause',
      () => HttpResponse.json(errorFixture, { status: 409 }),
      calls,
    );
    answeredCommand('resume', () => HttpResponse.json(taskRunning), calls);

    const row = createActor(rowActionMachine, { input: { taskId: 5, status: 'RUNNING' } }).start();
    row.send(activate('pause', 'RUNNING'));
    await pause.arrived;
    row.send({ type: 'STATUS', status: 'PAUSED' });
    row.send(activate('cancel', 'PAUSED', true));
    pause.release();

    await waitFor(row, (state) => state.matches('confirming'), { timeout: 2000 });
    row.send({ type: 'DISMISS' });
    const declined = row.getSnapshot();
    expect(declined.matches('ready')).toBe(true);
    expect(declined.context.error).toMatchObject({ code: 'not-found' });
    expect(calls).toEqual(['pause 5']);

    // The next action clears it.
    row.send(activate('resume', 'PAUSED'));
    expect(row.getSnapshot().context.error).toBeNull();
    await waitFor(row, (state) => state.matches({ ready: 'done' }), { timeout: 2000 });
    expect(calls).toEqual(['pause 5', 'resume 5']);
    row.stop();
  });
});
