import { assign, fromPromise, setup } from 'xstate';
import { getSettings } from '../api/client';
import type { Settings } from '../api/types';

// Loads the stored settings (item 32; ADR 0038: the loading/loaded/failed flow is machine
// state, not React state). It starts loading at once; a failure keeps its error, and RETRY
// fetches again.
export const settingsMachine = setup({
  types: {
    context: {} as { settings: Settings | null; error: unknown },
    events: {} as { type: 'RETRY' },
  },
  actors: {
    fetchSettings: fromPromise<Settings>(() => getSettings()),
  },
}).createMachine({
  id: 'settings',
  context: { settings: null, error: null },
  initial: 'loading',
  states: {
    loading: {
      invoke: {
        src: 'fetchSettings',
        onDone: { target: 'loaded', actions: assign({ settings: ({ event }) => event.output }) },
        onError: { target: 'failed', actions: assign({ error: ({ event }) => event.error }) },
      },
    },
    loaded: {},
    failed: {
      on: { RETRY: { target: 'loading', actions: assign({ error: null }) } },
    },
  },
});
