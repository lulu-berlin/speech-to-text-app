// A plain expand/collapse switch (item 14, ADR 0038): the advanced options panel's
// toggle is a tiny state machine rather than a boolean.
import { setup } from 'xstate';

export const toggleMachine = setup({
  types: { events: {} as { type: 'TOGGLE' } },
}).createMachine({
  id: 'toggle',
  initial: 'closed',
  states: {
    closed: { on: { TOGGLE: 'open' } },
    open: { on: { TOGGLE: 'closed' } },
  },
});
