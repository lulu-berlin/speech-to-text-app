import { assign, setup } from 'xstate';

// The audio playhead on the /audio timeline in milliseconds (ADR 0038; items 8–10). The
// audio element's timeupdate/seeked events and the click-to-seek flow report the current
// position; the transcript derives the highlighted segment from it. A machine so the
// transition (position change) is state-machine owned, not React state.
export const playbackMachine = setup({
  types: {
    context: {} as { currentTimeMs: number },
    events: {} as { type: 'PLAYHEAD'; ms: number },
  },
}).createMachine({
  id: 'playback',
  context: { currentTimeMs: 0 },
  on: {
    PLAYHEAD: { actions: assign({ currentTimeMs: ({ event }) => event.ms }) },
  },
});
