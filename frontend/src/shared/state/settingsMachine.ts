import { assign, fromPromise, setup } from 'xstate';
import { dismissDeviceFallback, getSettings } from '../api/client';
import type { Settings, SettingsNotice, SettingsUpdate } from '../api/types';

// The stored settings, loaded once and kept current (item 32; ADR 0038: the flows are
// machine states, not React state). It starts loading at once; a failure keeps its error,
// and RETRY fetches again. Once loaded: UPDATED applies a settings-updated event (the
// device choices stay those of the fetch: the startup probe's answer does not change
// while the server runs); DISMISS clears the CPU-fallback notice on the server (a failed
// dismiss keeps the notice, so the banner stays and can be dismissed again).
// The save is the view's call — it knows the form — and reports back: SAVE_DONE applies
// the stored answer, or a rejection (the ApiError) which keeps the current settings, the
// error for the form's inline rejection and a bumped [rejections], which a view watches
// to restore its fields from the stored settings.
export const settingsMachine = setup({
  types: {
    context: {} as { settings: Settings | null; error: unknown; rejections: number },
    events: {} as
      | { type: 'RETRY' }
      | { type: 'UPDATED'; update: SettingsNotice }
      | { type: 'DISMISS' }
      | { type: 'SAVE_DONE'; settings: Settings | SettingsUpdate; error: unknown },
  },
  actors: {
    fetchSettings: fromPromise<Settings>(() => getSettings()),
    dismissFallback: fromPromise<Settings>(() => dismissDeviceFallback()),
  },
}).createMachine({
  id: 'settings',
  context: { settings: null, error: null, rejections: 0 },
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
                : {
                    ...event.update,
                    availableDevices: context.settings.availableDevices,
                  },
            error: null,
          }),
        },
        SAVE_DONE: {
          actions: assign(({ context, event }) =>
            event.error === null
              ? {
                  error: null,
                  settings: event.settings as Settings,
                  rejections: context.rejections,
                }
              : {
                  error: event.error,
                  settings: {
                    ...event.settings,
                    deviceFallback: context.settings?.deviceFallback ?? null,
                    availableDevices: context.settings?.availableDevices ?? [],
                  },
                  rejections: context.rejections + 1,
                },
          ),
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
