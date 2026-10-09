import { http, HttpResponse } from 'msw';
import { createActor, waitFor } from 'xstate';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/server';
import { eventStreamResponse } from '../api/handlers';
import {
  error as errorFixture,
  queue,
  snapshot,
  taskDeleted,
  taskPaused,
  taskProgress,
  taskQueued,
  taskRunning,
} from '../api/fixtures';
import { connectionMachine } from './connectionMachine';

describe('the connection machine', () => {
  it('goes from connecting to live on the first snapshot, which replaces tasks and queue', async () => {
    const actor = createActor(connectionMachine).start();
    expect(actor.getSnapshot().value).toBe('connecting');

    const live = await waitFor(actor, (state) => state.matches('live'));

    // Expected state is the snapshot event's payload (docs/api/snapshot.json).
    expect(live.context.tasks).toEqual(snapshot.tasks);
    expect(live.context.queue).toEqual(snapshot.queue);
    actor.stop();
  });

  it('applies task-updated, task-progress, queue-updated and task-deleted events', async () => {
    // Mock addition: task-updated carries task.paused.json (id 7, QUEUED in the snapshot)
    // and queue-updated carries queue.json with paused: true, because the stock examples
    // equal what snapshot.json already holds, so applying them would change nothing.
    const pausedQueue = { ...queue, paused: true };
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          ['snapshot', snapshot],
          ['task-updated', taskPaused],
          ['task-progress', taskProgress],
          ['queue-updated', pausedQueue],
          ['task-deleted', taskDeleted],
        ]),
      ),
    );
    const actor = createActor(connectionMachine).start();

    // task-deleted is sent last, so once task 4 is gone from the live state every event
    // has been applied.
    const updated = await waitFor(
      actor,
      (state) =>
        state.matches('live') && !state.context.tasks.some((task) => task.id === taskDeleted.id),
      { timeout: 2000 },
    );

    // snapshot.json holds tasks 5 (RUNNING), 7 (QUEUED), 3 (COMPLETED), 4 (FAILED).
    const [running, , completed] = snapshot.tasks;
    expect(updated.context.tasks).toEqual([
      {
        ...running,
        transcriptionProgress: taskProgress.transcriptionProgress,
        diarizationProgress: taskProgress.diarizationProgress,
      },
      taskPaused,
      completed,
    ]);
    expect(updated.context.queue).toEqual(pausedQueue);
    actor.stop();
  });

  it('goes to reconnecting when the stream drops, and the next snapshot replaces the state', async () => {
    // Mock additions: the first connection sends a retry hint of 50 ms (so the test does not
    // wait for EventSource's default reconnect delay) and then ends, which is a drop; the
    // second connection's snapshot is snapshot.json without task 4, as if task 4 had been
    // deleted while the client was disconnected.
    const snapshotWithoutTask4 = {
      ...snapshot,
      tasks: snapshot.tasks.filter((task) => task.id !== 4),
    };
    let connections = 0;
    server.use(
      http.get('/api/events', () => {
        connections += 1;
        return connections === 1
          ? eventStreamResponse([['snapshot', snapshot]], { retryMs: 50, end: true })
          : eventStreamResponse([['snapshot', snapshotWithoutTask4]]);
      }),
    );
    const actor = createActor(connectionMachine);
    const visited: unknown[] = [];
    actor.subscribe((state) => {
      if (visited.at(-1) !== state.value) {
        visited.push(state.value);
      }
    });
    actor.start();

    // No task-deleted event is sent, so task 4 disappears only if the second snapshot
    // replaces the state.
    const reconnected = await waitFor(
      actor,
      (state) => state.matches('live') && !state.context.tasks.some((task) => task.id === 4),
      { timeout: 2000 },
    );

    expect(visited).toEqual(['connecting', 'live', 'reconnecting', 'live']);
    expect(reconnected.context.tasks).toEqual(snapshotWithoutTask4.tasks);
    expect(reconnected.context.queue).toEqual(snapshotWithoutTask4.queue);
    actor.stop();
  });

  it('applies a task update that breaks the transition table and logs it', async () => {
    // snapshot.json holds task 3 as COMPLETED; task-transitions.json gives COMPLETED no
    // transitions, so RUNNING is not allowed from it.
    // Mock addition: task.running.json with id 3, because the example's own id (5) is
    // already RUNNING in the snapshot and would not be a status change.
    const illegalUpdate = { ...taskRunning, id: 3 };
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          ['snapshot', snapshot],
          ['task-updated', illegalUpdate],
        ]),
      ),
    );
    const actor = createActor(connectionMachine).start();

    const applied = await waitFor(
      actor,
      (state) =>
        state.matches('live') &&
        state.context.tasks.some((task) => task.id === 3 && task.status === 'RUNNING'),
      { timeout: 2000 },
    );

    // Applied, not rejected: the COMPLETED task 3 is replaced by the RUNNING update.
    const expected = snapshot.tasks.map((task) => (task.id === 3 ? illegalUpdate : task));
    expect(applied.context.tasks).toEqual(expected);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0]?.[0]).toContain('COMPLETED');
    actor.stop();
    warn.mockRestore();
  });

  it('adds a task-updated for a task the state does not hold yet', async () => {
    // Mock addition: task.queued.json with id 99, which no snapshot task has, as a new
    // upload would arrive through task-updated.
    const added = { ...taskQueued, id: 99 };
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          ['snapshot', snapshot],
          ['task-updated', added],
        ]),
      ),
    );
    const actor = createActor(connectionMachine).start();

    const updated = await waitFor(
      actor,
      (state) => state.matches('live') && state.context.tasks.some((task) => task.id === 99),
      { timeout: 2000 },
    );

    expect(updated.context.tasks).toEqual([...snapshot.tasks, added]);
    actor.stop();
  });

  it('reconnects by itself when the server answers a connection with an HTTP error', async () => {
    // Mock addition: the first connection is answered 500 with error.json, so EventSource
    // stops retrying (its readyState becomes CLOSED); later connections get the snapshot.
    let connections = 0;
    server.use(
      http.get('/api/events', () => {
        connections += 1;
        return connections === 1
          ? HttpResponse.json(errorFixture, { status: 500 })
          : eventStreamResponse([['snapshot', snapshot]]);
      }),
    );
    const actor = createActor(connectionMachine).start();

    const live = await waitFor(actor, (state) => state.matches('live'), { timeout: 5000 });

    expect(connections).toBeGreaterThan(1);
    expect(live.context.tasks).toEqual(snapshot.tasks);
    actor.stop();
  }, 10000);
});
