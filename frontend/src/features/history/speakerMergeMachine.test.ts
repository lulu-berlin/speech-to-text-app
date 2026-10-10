import { http, HttpResponse } from 'msw';
import { createActor, waitFor } from 'xstate';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/server';
import { error as errorFixture, speakers as speakersFixture } from '../../shared/api/fixtures';
import { ApiError } from '../../shared/api/types';
import type { Speakers } from '../../shared/api/types';
import { speakerMergeMachine } from './speakerMergeMachine';

// ADR 0038: the speakers panel's merge flow as pure logic (events in, state and context out).
describe('the speaker merge machine', () => {
  it('confirms only a complete group, keeps the survivor in it, and sends nothing until CONFIRM', async () => {
    const posted: unknown[] = [];
    let refuse = false;
    server.use(
      http.post('/api/tasks/:id/speakers/merge', async ({ request }) => {
        posted.push(await request.json());
        if (refuse) {
          // error.json stands in for the 400 body, with its code changed.
          return HttpResponse.json({ ...errorFixture, error: 'invalid-merge' }, { status: 400 });
        }
        return HttpResponse.json(speakersFixture);
      }),
    );
    const merged = vi.fn<(speakers: Speakers) => void>();
    const actor = createActor(
      speakerMergeMachine.provide({ actions: { merged: (_, speakers) => merged(speakers) } }),
      { input: { taskId: 3 } },
    ).start();
    expect(actor.getSnapshot().value).toBe('closed');
    actor.send({ type: 'OPEN' });

    // Fewer than two speakers: no confirmation.
    actor.send({ type: 'REQUEST' });
    actor.send({ type: 'TOGGLE', speakerId: 1 });
    actor.send({ type: 'REQUEST' });
    expect(actor.getSnapshot().value).toBe('editing');

    // The first checked speaker is the survivor until one is picked; an unchecked survivor
    // gives way to the first speaker left in the group.
    actor.send({ type: 'TOGGLE', speakerId: 0 });
    expect(actor.getSnapshot().context).toMatchObject({ group: [1, 0], into: 1 });
    actor.send({ type: 'KEEP', speakerId: 0 });
    actor.send({ type: 'TOGGLE', speakerId: 0 });
    expect(actor.getSnapshot().context).toMatchObject({ group: [1], into: 1 });
    actor.send({ type: 'TOGGLE', speakerId: 0 });
    actor.send({ type: 'KEEP', speakerId: 0 });

    // Confirmation: CANCEL goes back to the form and sends nothing.
    actor.send({ type: 'REQUEST' });
    expect(actor.getSnapshot().value).toBe('confirming');
    actor.send({ type: 'CANCEL' });
    expect(actor.getSnapshot().value).toBe('editing');
    expect(posted).toEqual([]);

    // A refusal returns to the form with the typed error; nothing merged.
    refuse = true;
    actor.send({ type: 'REQUEST' });
    actor.send({ type: 'CONFIRM' });
    const refused = await waitFor(actor, (state) => state.context.error !== null, {
      timeout: 2000,
    });
    expect(refused.value).toBe('editing');
    expect(refused.context.error).toBeInstanceOf(ApiError);
    expect(refused.context.error).toMatchObject({ code: 'invalid-merge' });
    expect(merged).not.toHaveBeenCalled();

    // Success: the group goes out sorted, the form closes, and `merged` gets the 200.
    refuse = false;
    actor.send({ type: 'REQUEST' });
    actor.send({ type: 'CONFIRM' });
    const done = await waitFor(actor, (state) => state.matches('closed'), { timeout: 2000 });
    expect(done.value).toBe('closed');
    expect(posted).toEqual([
      { group: [0, 1], into: 0 },
      { group: [0, 1], into: 0 },
    ]);
    expect(merged).toHaveBeenCalledWith(speakersFixture);
    actor.stop();
  });
});
