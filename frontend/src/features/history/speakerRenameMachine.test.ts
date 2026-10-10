import { http, HttpResponse } from 'msw';
import { createActor, waitFor } from 'xstate';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/server';
import { error as errorFixture } from '../../shared/api/fixtures';
import { ApiError } from '../../shared/api/types';
import type { Speakers } from '../../shared/api/types';
import { speakerRenameMachine } from './speakerRenameMachine';

// ADR 0038: the speakers panel's rename flow as pure logic (events in, state and context out).
describe('the speaker rename machine', () => {
  it('sends nothing for an unchanged name, restores the stored name on a refusal, and resets through the server', async () => {
    const sent: unknown[] = [];
    let refuse = false;
    server.use(
      http.put('/api/tasks/:id/speakers/:speakerId', async ({ request }) => {
        const body = (await request.json()) as { speaker: string | null };
        sent.push(body);
        if (refuse) {
          // error.json stands in for the 409 body, with its code changed.
          return HttpResponse.json(
            { ...errorFixture, error: 'duplicate-speaker-name' },
            { status: 409 },
          );
        }
        // The server writes the default name for a reset (N = speakerId + 1).
        return HttpResponse.json({
          speakers: [{ speakerId: 1, speaker: body.speaker ?? 'Speaker 2' }],
        });
      }),
    );
    const renamed = vi.fn<(speakers: Speakers) => void>();
    const actor = createActor(
      speakerRenameMachine.provide({ actions: { renamed: (_, speakers) => renamed(speakers) } }),
      { input: { taskId: 3, speakerId: 1, stored: 'Bob' } },
    ).start();

    // Unchanged once trimmed: no request, and the field shows the stored name again.
    actor.send({ type: 'EDIT', value: '  Bob ' });
    actor.send({ type: 'SAVE' });
    expect(actor.getSnapshot().value).toBe('idle');
    expect(actor.getSnapshot().context.draft).toBe('Bob');
    expect(sent).toEqual([]);

    // Refused: back to the stored name, the typed error held, nothing renamed.
    refuse = true;
    actor.send({ type: 'EDIT', value: 'Alice' });
    actor.send({ type: 'SAVE' });
    expect(actor.getSnapshot().value).toBe('sending');
    const refused = await waitFor(actor, (state) => state.context.error !== null, {
      timeout: 2000,
    });
    expect(refused.value).toBe('idle');
    expect(refused.context.draft).toBe('Bob');
    expect(refused.context.stored).toBe('Bob');
    expect(refused.context.error).toBeInstanceOf(ApiError);
    expect(refused.context.error).toMatchObject({ code: 'duplicate-speaker-name' });
    expect(renamed).not.toHaveBeenCalled();

    // A reset sends null and takes the name the server answered.
    refuse = false;
    actor.send({ type: 'RESET' });
    const reset = await waitFor(actor, (state) => state.context.stored === 'Speaker 2', {
      timeout: 2000,
    });
    expect(reset.value).toBe('idle');
    expect(reset.context.draft).toBe('Speaker 2');
    expect(reset.context.error).toBeNull();
    expect(sent).toEqual([{ speaker: 'Alice' }, { speaker: null }]);
    expect(renamed).toHaveBeenCalledWith({ speakers: [{ speakerId: 1, speaker: 'Speaker 2' }] });
    actor.stop();
  });
});
