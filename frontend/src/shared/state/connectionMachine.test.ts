import { createActor, waitFor } from 'xstate';
import { describe, expect, it } from 'vitest';
import { snapshot } from '../api/fixtures';
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
});
