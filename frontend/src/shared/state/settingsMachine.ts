import { assign, fromPromise, setup } from 'xstate';
import { dismissDeviceFallback, getSettings, resetSettings, saveSettings } from '../api/client';
import type { Settings, SettingsNotice, SettingsUpdate } from '../api/types';

// One write of the settings queue: the whole settings, or the reset to the server's defaults
// (ADR 0062). The reset joins the same queue as a write.
type SettingsWrite = { type: 'settings'; settings: SettingsUpdate } | { type: 'reset' };

// The answer of a write or a dismissal, applied only when no settings-updated arrived while
// it ran: the event is newer than an answer that was already on its way (ADR 0062).
function answerIfFresh(
  context: { appliedUpdates: number; startedUpdates: number },
  output: Settings,
): { settings?: Settings } {
  return context.startedUpdates === context.appliedUpdates ? { settings: output } : {};
}

// The stored settings, loaded once and kept current (item 32; ADR 0038: the flows are
// machine states, not React state). It starts loading at once; a failure keeps its error,
// and RETRY fetches again. UPDATED applies a settings-updated event (the device choices
// stay those of the fetch: the startup probe's answer does not change while the server
// runs) and keeps a shown rejection until the user answers it; one that arrives before the
// settings are stored is kept and laid over the fetch answer when it lands, since the event
// is the newer settings (the server emits settings-updated for every write). DISMISS
// clears the CPU-fallback notice on the server (a failed dismiss keeps the notice, so the
// banner stays and can be dismissed again).
// The page saves itself (item 72, ADR 0062): the view sends SAVE with the settings as a
// whole, the machine writes them one at a time, and a SAVE that arrives while a write runs
// collapses into one follow-up with the latest values. ASK_RESET opens the confirmation
// (confirming), and CONFIRM_RESET queues the reset — DELETE /api/settings — in that same
// one-write queue; its answer, the defaults, replaces the stored settings. A done applies
// its answer only when no UPDATED arrived since the operation started: a settings-updated
// is newer than an answer that was already on its way, so the event's settings stand (the
// server emits settings-updated for every write); this holds for a dismissal too.
// A rejection keeps the stored settings, the error for the form and a bumped
// [rejections], which the view watches to put its fields back.
export const settingsMachine = setup({
  types: {
    context: {} as {
      settings: Settings | null;
      error: unknown;
      // The last dismissal's failure: the banner shows it where the notice is (item 72).
      dismissError: unknown;
      rejections: number;
      // The write being run, and the one latest write waiting behind it.
      writing: SettingsWrite | null;
      queued: SettingsWrite | null;
      // The settings-updated events applied so far, and its value when the running server
      // operation started: an answer is applied only if no event arrived meanwhile, since
      // the event is the newer settings (ADR 0062).
      appliedUpdates: number;
      startedUpdates: number;
      // The latest settings-updated received before any settings were stored: the load
      // lays it over the fetch answer, which may predate it (ADR 0062).
      pendingUpdate: SettingsNotice | null;
      // Set by a rejected write that had a follow-up waiting: the view rebuilds that write
      // from the form as it stands after the rejected field's revert (ADR 0062).
      recommit: boolean;
    },
    events: {} as
      | { type: 'RETRY' }
      | { type: 'UPDATED'; update: SettingsNotice }
      | { type: 'DISMISS' }
      | { type: 'SAVE'; settings: SettingsUpdate }
      | { type: 'ASK_RESET' }
      | { type: 'CONFIRM_RESET' }
      | { type: 'CANCEL_RESET' },
  },
  actors: {
    fetchSettings: fromPromise<Settings>(() => getSettings()),
    dismissFallback: fromPromise<Settings>(() => dismissDeviceFallback()),
    save: fromPromise<Settings, SettingsWrite>(({ input }) =>
      input.type === 'reset' ? resetSettings() : saveSettings(input.settings),
    ),
  },
}).createMachine({
  id: 'settings',
  context: {
    settings: null,
    error: null,
    dismissError: null,
    rejections: 0,
    writing: null,
    queued: null,
    appliedUpdates: 0,
    startedUpdates: 0,
    pendingUpdate: null,
    recommit: false,
  },
  initial: 'loading',
  on: {
    UPDATED: {
      actions: assign({
        settings: ({ context, event }) =>
          context.settings === null
            ? null
            : {
                ...event.update,
                availableDevices: context.settings.availableDevices,
                gpuNames: context.settings.gpuNames,
                deviceProbeError: context.settings.deviceProbeError,
              },
        // Before the load answers there is no baseline to lay the event over yet.
        pendingUpdate: ({ context, event }) => (context.settings === null ? event.update : null),
        appliedUpdates: ({ context }) => context.appliedUpdates + 1,
        // A shown rejection stays until the user answers it: a successful write or
        // RETRY clears it, a settings-updated does not (ADR 0062).
      }),
    },
  },
  states: {
    loading: {
      invoke: {
        src: 'fetchSettings',
        onDone: {
          target: 'loaded',
          actions: assign(({ context, event }) => ({
            settings:
              context.pendingUpdate === null
                ? event.output
                : { ...event.output, ...context.pendingUpdate },
            pendingUpdate: null,
          })),
        },
        onError: { target: 'failed', actions: assign({ error: ({ event }) => event.error }) },
      },
    },
    loaded: {
      initial: 'idle',
      states: {
        idle: {
          on: {
            DISMISS: {
              target: 'dismissing',
              actions: assign({
                dismissError: null,
                startedUpdates: ({ context }) => context.appliedUpdates,
              }),
            },
            // The reset asks first: the dialog is this state (ADR 0062).
            ASK_RESET: 'confirming',
            SAVE: {
              target: 'saving',
              actions: assign({
                writing: ({ event }) => ({ type: 'settings', settings: event.settings }),
                queued: null,
                startedUpdates: ({ context }) => context.appliedUpdates,
                recommit: false,
              }),
            },
          },
        },
        confirming: {
          on: {
            CONFIRM_RESET: {
              target: 'saving',
              actions: assign({
                writing: { type: 'reset' },
                queued: null,
                startedUpdates: ({ context }) => context.appliedUpdates,
              }),
            },
            CANCEL_RESET: 'idle',
          },
        },
        dismissing: {
          invoke: {
            src: 'dismissFallback',
            onDone: {
              target: 'idle',
              actions: assign(({ context, event }) => ({
                ...answerIfFresh(context, event.output),
                // A shown rejection is the user's to answer: a dismissal does not clear it.
                dismissError: null,
              })),
            },
            onError: {
              target: 'idle',
              actions: assign({ dismissError: ({ event }) => event.error }),
            },
          },
        },
        saving: {
          on: {
            // One write at a time: a change made while it runs replaces the follow-up, so
            // the next write carries the latest values.
            SAVE: {
              actions: assign({
                queued: ({ event }) => ({ type: 'settings', settings: event.settings }),
                recommit: false,
              }),
            },
          },
          invoke: {
            src: 'save',
            input: ({ context }) => {
              if (context.writing === null) {
                // Unreachable: saving is entered only with a body.
                throw new Error('settings write started without a body');
              }
              return context.writing;
            },
            onDone: [
              {
                guard: ({ context }) => context.queued !== null,
                target: 'saving',
                // A self-transition must re-enter so the queued body starts its own write.
                reenter: true,
                actions: assign(({ context, event }) => ({
                  ...answerIfFresh(context, event.output),
                  error: null,
                  writing: context.queued,
                  queued: null,
                  // The queued write starts now: events from here on are newer than its answer.
                  startedUpdates: context.appliedUpdates,
                  recommit: false,
                })),
              },
              {
                target: 'idle',
                actions: assign(({ context, event }) => ({
                  ...answerIfFresh(context, event.output),
                  error: null,
                  writing: null,
                  recommit: false,
                })),
              },
            ],
            onError: {
              target: 'idle',
              actions: assign(({ context, event }) => ({
                error: event.error,
                rejections: context.rejections + 1,
                writing: null,
                // The rejected body is dropped; a follow-up that was waiting is rebuilt by
                // the view from the form after this rejection's revert (ADR 0062).
                queued: null,
                recommit: context.queued !== null,
              })),
            },
          },
        },
      },
    },
    failed: {
      on: { RETRY: { target: 'loading', actions: assign({ error: null }) } },
    },
  },
});
