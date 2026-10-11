import { setup } from 'xstate';

// Follow mode (item 10, ADR 0038): while following, the transcript keeps the active
// segment in view as the playhead moves; the user scrolling by hand stops following, and
// "Back to playing position" resumes it. SCROLLED is only sent for input evidence (wheel,
// touch, the scroll keys, a scrollbar drag — item 36); a bare scroll event is never one,
// so the view's own follow scroll cannot stop following.
export const followModeMachine = setup({
  types: {
    context: {} as Record<string, never>,
    events: {} as { type: 'SCROLLED' } | { type: 'RESUME' },
  },
}).createMachine({
  id: 'followMode',
  context: {},
  initial: 'following',
  states: {
    following: { on: { SCROLLED: 'stopped' } },
    stopped: { on: { RESUME: 'following' } },
  },
});
