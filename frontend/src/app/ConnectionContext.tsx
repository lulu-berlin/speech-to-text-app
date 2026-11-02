import { createActorContext } from '@xstate/react';
import { connectionMachine } from '../shared/state/connectionMachine';

// One connection to GET /api/events for the whole app. The provider is rendered by App,
// so every view and the header read the same live state.
export const ConnectionContext = createActorContext(connectionMachine);

// What a disabled write control says (item 87): the header's "Reconnecting" is the banner,
// this is the control's own explanation. "Service", never "backend", in what a user reads.
export const WRITES_UNAVAILABLE = "Can't reach the app's service. Reconnecting…";

// One notion of "writes enabled" (item 87): true only while the live stream is up, so no
// panel carries its own copy of the check. A control that writes adds `writes.disabled` to
// its own disabled state and shows `writes.title`; reading and local interaction do not
// consult it.
export function useWriteControl(): { disabled: boolean; title: string | undefined } {
  const live = ConnectionContext.useSelector((state) => state.matches('live'));
  return live
    ? { disabled: false, title: undefined }
    : { disabled: true, title: WRITES_UNAVAILABLE };
}
