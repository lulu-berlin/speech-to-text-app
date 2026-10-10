// The searchable select's interaction (item 14, ADR 0038): closed, or open with a
// filter query and a keyboard-active option. The option list itself stays with the
// component (options are props); the machine holds only the interaction, so the select
// shows the chosen option's label whenever it is closed.
import { assign, setup } from 'xstate';

interface ComboboxContext {
  query: string;
  activeIndex: number;
}

type ComboboxEvent =
  | { type: 'OPEN' }
  | { type: 'CLOSE' }
  | { type: 'QUERY'; value: string }
  | { type: 'ARROW_DOWN'; max: number }
  | { type: 'ARROW_UP'; max: number };

export const comboboxMachine = setup({
  types: {
    context: {} as ComboboxContext,
    events: {} as ComboboxEvent,
  },
  actions: {
    // Opening the popup shows every option; typing filters from the top.
    resetQuery: assign({ query: '', activeIndex: 0 }),
    setQuery: assign({
      query: ({ event }) => (event.type === 'QUERY' ? event.value : ''),
      activeIndex: 0,
    }),
    moveDown: assign({
      activeIndex: ({ context, event }) =>
        event.type === 'ARROW_DOWN'
          ? Math.min(context.activeIndex + 1, event.max - 1)
          : context.activeIndex,
    }),
    moveUp: assign({
      activeIndex: ({ context, event }) =>
        event.type === 'ARROW_UP' ? Math.max(context.activeIndex - 1, 0) : context.activeIndex,
    }),
  },
}).createMachine({
  id: 'combobox',
  context: { query: '', activeIndex: 0 },
  initial: 'closed',
  states: {
    closed: {
      on: {
        OPEN: { target: 'open', actions: 'resetQuery' },
        ARROW_DOWN: { target: 'open', actions: 'resetQuery' },
        QUERY: { target: 'open', actions: 'setQuery' },
      },
    },
    open: {
      on: {
        QUERY: { actions: 'setQuery' },
        ARROW_DOWN: { actions: 'moveDown' },
        ARROW_UP: { actions: 'moveUp' },
        CLOSE: { target: 'closed' },
      },
    },
  },
});
