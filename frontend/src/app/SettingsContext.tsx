import { createActorContext } from '@xstate/react';
import { useEffect } from 'react';
import { settingsMachine } from '../shared/state/settingsMachine';
import { ConnectionContext } from './ConnectionContext';

// One settings instance for the whole app (item 72), like ConnectionContext: every view and
// the CPU-fallback banner read the same stored settings through it, so one GET serves them
// all and a save in one view is the same state in the other. A module singleton would leak
// between tests; this provider dies with the app it is rendered in.
export const SettingsContext = createActorContext(settingsMachine);

// Forwards the connection's settings-updated event (another tab's write, a dismissal, the
// engine's CPU fallback) into the one settings actor. Rendered once by App, inside both
// providers.
export function SettingsSync() {
  const update = ConnectionContext.useSelector((state) => state.context.settingsUpdate);
  const settings = SettingsContext.useActorRef();

  useEffect(() => {
    if (update !== null) {
      settings.send({ type: 'UPDATED', update });
    }
  }, [update, settings]);

  return null;
}
