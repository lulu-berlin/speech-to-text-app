import { assign, fromPromise, setup } from 'xstate';
import { dismissDeviceFallback, getSettings } from '../api/client';
import type { Settings, SettingsUpdate } from '../api/types';

// Loads the stored settings and keeps them current (item 32; ADR 0038: the flows are machine
// states, not React state). It starts loading at once; a failure keeps its error, and RETRY
// fetches again. Once loaded, UPDATED applies a settings-updated event (the device choices
// stay those of the fetch: the startup probe's answer does not change while the server
// runs), and DISMISS clears the CPU-fallback notice on the server; a failed dismiss keeps
// the notice, so the banner stays and can be dismissed again.
export const settingsMachine = setup({
  types: {
    context: {} as { settings: Settings | null; error: unknown },
    events: {} as
      { type: 'RETRY' } | { type: 'UPDATED'; update: SettingsUpdate } | { type: 'DISMISS' },
  },
  actors: {
    fetchSettings: fromPromise<Settings>(() => getSettings()),
    dismissFallback: fromPromise<Settings>(() => dismissDeviceFallback()),
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
    loaded: {
      on: {
        UPDATED: {
          actions: assign({
            settings: ({ context, event }) =>
              context.settings === null
                ? null
                : { ...event.update, availableDevices: context.settings.availableDevices },
          }),
        },
      },
      initial: 'idle',
      states: {
        idle: {
          on: { DISMISS: 'dismissing' },
        },
        dismissing: {
          invoke: {
            src: 'dismissFallback',
            onDone: {
              target: 'idle',
              actions: assign({ settings: ({ event }) => event.output, error: null }),
            },
            onError: { target: 'idle', actions: assign({ error: ({ event }) => event.error }) },
          },
        },
      },
    },
    failed: {
      on: { RETRY: { target: 'loading', actions: assign({ error: null }) } },
    },
  },
});
