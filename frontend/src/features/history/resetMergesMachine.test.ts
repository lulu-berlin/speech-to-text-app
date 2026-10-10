import { http, HttpResponse } from 'msw';
import { createActor, waitFor } from 'xstate';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/server';
import { error as errorFixture, speakers as speakersFixture } from '../../shared/api/fixtures';
import { ApiError } from '../../shared/api/types';
import type { Speakers } from '../../shared/api/types';
import { resetMergesMachine } from './speakerMergeMachine';

// ADR 0038: "Reset all merges" as pure logic (events in, state and context out).
describe('the reset-merges machine', () => {
  it('holds the typed error of a refusal without resetting, and runs reset with the 200 on success', async () => {
    let refuse = true;
    let requests = 0;
    server.use(
      http.delete('/api/tasks/:id/speakers/merges', () => {
        requests += 1;
        // error.json stands in for a 409 not-completed body, with its code changed.
        return refuse
          ? HttpResponse.json({ ...errorFixture, error: 'not-completed' }, { status: 409 })
          : HttpResponse.json(speakersFixture);
      }),
    );
    const reset = vi.fn<(speakers: Speakers) => void>();
    const actor = createActor(
      resetMergesMachine.provide({ actions: { reset: (_, speakers) => reset(speakers) } }),
      { input: { taskId: 3 } },
    ).start();
    expect(actor.getSnapshot().value).toBe('idle');

    actor.send({ type: 'RESET' });
    expect(actor.getSnapshot().value).toBe('sending');
    const refused = await waitFor(actor, (state) => state.context.error !== null, {
      timeout: 2000,
    });
    expect(refused.value).toBe('idle');
    expect(refused.context.error).toBeInstanceOf(ApiError);
    expect(refused.context.error).toMatchObject({ code: 'not-completed' });
    expect(reset).not.toHaveBeenCalled();

    // A later success clears the error and hands the 200 to the view.
    refuse = false;
    actor.send({ type: 'RESET' });
    const done = await waitFor(
      actor,
      (state) => reset.mock.calls.length > 0 && state.matches('idle'),
      {
        timeout: 2000,
      },
    );
    expect(done.context.error).toBeNull();
    expect(reset).toHaveBeenCalledWith(speakersFixture);
    expect(requests).toBe(2);
    actor.stop();
  });
});
