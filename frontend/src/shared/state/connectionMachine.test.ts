import { http, HttpResponse } from 'msw';
import { createActor, fromCallback, waitFor } from 'xstate';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/server';
import { eventStreamResponse } from '../api/handlers';
import { subscribeToEvents } from '../api/client';
import type { StreamEvent } from '../api/types';
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
    // Mock additions: task-updated carries task.paused.json (id 7, QUEUED in the snapshot)
    // and task.running.json with id 6 (the progress example's id, which no snapshot task
    // has); queue-updated carries queue.json with paused: true. The stock examples equal
    // what snapshot.json already holds, so applying them would change nothing.
    const pausedQueue = { ...queue, paused: true };
    const runningAs6 = { ...taskRunning, id: 6 };
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          ['snapshot', snapshot],
          ['task-updated', taskPaused],
          ['task-updated', runningAs6],
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

    // snapshot.json holds tasks 5 (RUNNING), 7 (QUEUED), 3 (COMPLETED), 4 (FAILED). Task 5
    // is untouched: the progress event names id 6, which the stream added just before.
    const [running, , completed] = snapshot.tasks;
    expect(updated.context.tasks).toEqual([
      running,
      taskPaused,
      completed,
      {
        ...runningAs6,
        transcriptionProgress: taskProgress.transcriptionProgress,
        diarizationStage: taskProgress.diarizationStage,
        diarizationProgress: taskProgress.diarizationProgress,
      },
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

  it('treats a stream without any event for the idle timeout as dropped, and stays live while heartbeats arrive', async () => {
    // ADR 0044: any event, a heartbeat included, proves the stream alive; the idle timeout
    // without one means the connection is lost though EventSource has not noticed (a silent
    // partition). The real timeout is 15 s; the stream actor is given 300 ms here, and a
    // 50 ms reconnect delay.
    // Mock additions: the first connection sends the snapshot and then only what the test
    // pushes; the second sends snapshot.json without task 4, as if task 4 had been deleted
    // while the client was cut off.
    const snapshotWithoutTask4 = {
      ...snapshot,
      tasks: snapshot.tasks.filter((task) => task.id !== 4),
    };
    const encoder = new TextEncoder();
    const frame = (name: string, data: unknown) =>
      encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);
    let first: ReadableStreamDefaultController<Uint8Array> | undefined;
    let connections = 0;
    server.use(
      http.get('/api/events', () => {
        connections += 1;
        if (connections > 1) {
          return eventStreamResponse([['snapshot', snapshotWithoutTask4]]);
        }
        return new HttpResponse(
          new ReadableStream<Uint8Array>({
            start(controller) {
              first = controller;
              controller.enqueue(frame('snapshot', snapshot));
            },
          }),
          { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' } },
        );
      }),
    );
    const machine = connectionMachine.provide({
      actors: {
        eventStream: fromCallback<StreamEvent>(({ sendBack }) =>
          subscribeToEvents(sendBack, 50, 300),
        ),
      },
    });
    const actor = createActor(machine);
    const visited: unknown[] = [];
    actor.subscribe((state) => {
      if (visited.at(-1) !== state.value) {
        visited.push(state.value);
      }
    });
    actor.start();
    await waitFor(actor, (state) => state.matches('live'));

    const stream = first;
    if (stream === undefined) {
      throw new Error('the first connection did not open');
    }

    // Heartbeats every 100 ms for a second, over three idle timeouts: still live, on the
    // first connection. Real time on purpose: the behavior is the timeout against a live
    // stream, and fake timers would also take over the timers the EventSource polyfill and
    // the MSW stream deliver frames with.
    for (let beat = 0; beat < 10; beat += 1) {
      const { promise: tick, resolve } = Promise.withResolvers<void>();
      setTimeout(resolve, 100);
      await tick;
      stream.enqueue(frame('heartbeat', {}));
    }
    expect(visited).toEqual(['connecting', 'live']);
    expect(connections).toBe(1);

    // The heartbeats stop: after the idle timeout the stream counts as dropped, and the next
    // connection's snapshot replaces the state (no task-deleted was ever sent).
    const reconnected = await waitFor(
      actor,
      (state) => state.matches('live') && !state.context.tasks.some((task) => task.id === 4),
      { timeout: 2000 },
    );
    expect(visited).toEqual(['connecting', 'live', 'reconnecting', 'live']);
    expect(connections).toBe(2);
    expect(reconnected.context.tasks).toEqual(snapshotWithoutTask4.tasks);
    actor.stop();
  });
});
