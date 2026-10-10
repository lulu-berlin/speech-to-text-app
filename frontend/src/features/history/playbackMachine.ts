import { assign, setup } from 'xstate';

// The audio playhead on the /audio timeline in milliseconds (ADR 0038; items 8–10), and
// whether the audio plays. The audio element's timeupdate/seeked events, the click-to-seek
// flow and the seek bar report the current position; the transcript derives the
// highlighted segment from it. The element's own play/pause/ended events drive
// paused/playing (item 25's themed player shows Play or Pause from it), so the state
// follows what the browser actually does, not the click.
export const playbackMachine = setup({
  types: {
    context: {} as { currentTimeMs: number },
    events: {} as { type: 'PLAYHEAD'; ms: number } | { type: 'PLAYED' } | { type: 'PAUSED' },
  },
}).createMachine({
  id: 'playback',
  context: { currentTimeMs: 0 },
  initial: 'paused',
  on: {
    PLAYHEAD: { actions: assign({ currentTimeMs: ({ event }) => event.ms }) },
  },
  states: {
    paused: { on: { PLAYED: 'playing' } },
    playing: { on: { PAUSED: 'paused' } },
  },
});
