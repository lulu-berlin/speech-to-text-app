import { assign, setup } from 'xstate';

// The audio playhead on the /audio timeline in milliseconds (ADR 0038; items 8–10), and
// whether the audio plays. The audio element's timeupdate/seeked events, the click-to-seek
// flow and the seek bar report the current position; the transcript derives the
// highlighted segment from it. The element's own play/pause/ended events drive
// paused/playing (item 25's themed player shows Play or Pause from it), so the state
// follows what the browser actually does, not the click.
//
// The seek bar (item 29): while the user drags it, the thumb's position is a draft
// ([dragMs]) — the audio keeps playing and the element's events keep updating
// [currentTimeMs], but the bar shows the draft, not the playhead, so the two never fight.
// Only a commit ([SEEK]) moves the audio; the element's `seeked` then confirms.
export const playbackMachine = setup({
  types: {
    context: {} as { currentTimeMs: number; dragMs: number | null },
    events: {} as
      | { type: 'PLAYHEAD'; ms: number }
      | { type: 'DRAG'; ms: number }
      | { type: 'SEEK'; ms: number }
      | { type: 'PLAYED' }
      | { type: 'PAUSED' },
  },
}).createMachine({
  id: 'playback',
  context: { currentTimeMs: 0, dragMs: null },
  initial: 'paused',
  on: {
    PLAYHEAD: { actions: assign({ currentTimeMs: ({ event }) => event.ms }) },
    DRAG: { actions: assign({ dragMs: ({ event }) => event.ms }) },
    SEEK: {
      actions: assign({ currentTimeMs: ({ event }) => event.ms, dragMs: () => null }),
    },
  },
  states: {
    paused: { on: { PLAYED: 'playing' } },
    playing: { on: { PAUSED: 'paused' } },
  },
});
