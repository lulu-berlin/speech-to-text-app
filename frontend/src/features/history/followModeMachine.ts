import { setup } from 'xstate';

// Follow mode (item 10, ADR 0038): while following, the transcript keeps the active
// segment in view as the playhead moves; the user scrolling by hand stops following, and
// "Back to playing position" resumes it. The scroll event fired by the view's own follow
// scroll is suppressed at the call site (a ref), so SCROLLED only arrives for hand scrolls.
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
