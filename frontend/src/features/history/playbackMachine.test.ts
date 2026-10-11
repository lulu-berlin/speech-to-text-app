import { createActor } from 'xstate';
import { describe, expect, it } from 'vitest';
import { playbackMachine } from './playbackMachine';

// The playback machine's drag draft (ADR 0031/0038 pure-logic tests): the seek bar shows
// the draft while the user drags, the audio element's events keep moving the playhead
// underneath it, and only a seek ends the draft and moves the audio.
describe('the playback machine', () => {
  it('keeps the seek bar draft while dragging and ends it with a seek', () => {
    const playback = createActor(playbackMachine).start();

    playback.send({ type: 'PLAYHEAD', ms: 6000 });
    expect(playback.getSnapshot().context).toEqual({ currentTimeMs: 6000, dragMs: null });

    // Drag ticks set the draft; the element's events (timeupdate while playing) keep
    // moving the playhead under it without clearing the draft.
    playback.send({ type: 'DRAG', ms: 5000 });
    playback.send({ type: 'PLAYHEAD', ms: 6400 });
    const dragging = playback.getSnapshot().context;
    expect(dragging.currentTimeMs).toBe(6400);
    expect(dragging.dragMs).toBe(5000);

    // The commit seeks: it moves the playhead and ends the draft; the element's seeked
    // event then confirms the position.
    playback.send({ type: 'SEEK', ms: 5000 });
    expect(playback.getSnapshot().context).toEqual({ currentTimeMs: 5000, dragMs: null });

    playback.stop();
  });
});
