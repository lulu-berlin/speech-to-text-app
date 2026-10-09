import { http } from 'msw';
import { createActor, waitFor } from 'xstate';
import { describe, expect, it } from 'vitest';
import { server } from '../../test/server';
import { eventStreamResponse } from '../api/handlers';
import { queue, snapshot, taskDeleted, taskPaused, taskProgress } from '../api/fixtures';
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
});
