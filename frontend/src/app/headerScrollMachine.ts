import { setup } from 'xstate';

// The header's size follows the page scroll (item 31): at the top of the page the
// mascot is full-size; once the page scrolls it shrinks to its compact size, and back
// at the top it grows again. The window scroll listener drives it (ADR 0038): SCROLLED
// whenever the page has scrolled, AT_TOP back at the top.
export const headerScrollMachine = setup({
  types: {
    context: {} as Record<string, never>,
    events: {} as { type: 'SCROLLED' } | { type: 'AT_TOP' },
  },
}).createMachine({
  id: 'headerScroll',
  context: {},
  initial: 'full',
  states: {
    full: { on: { SCROLLED: 'compact' } },
    compact: { on: { AT_TOP: 'full' } },
  },
});
