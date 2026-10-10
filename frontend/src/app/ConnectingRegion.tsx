import { LoadingState } from '../shared/ui/LoadingState';
import { ConnectionContext } from './ConnectionContext';

// The label follows the connection state: the first snapshot, or a drop (item 17).
const REGION_LABEL = {
  connecting: 'Connecting…',
  reconnecting: 'Reconnecting…',
} as const;

// The app's loading region (item 17): until the first snapshot arrives — and again while
// a dropped connection reconnects — the shell waits with the shared loading pattern (the
// mascot above a short label). It reads the state inside the connection provider, so it
// is its own component.
export function ConnectingRegion() {
  const state = ConnectionContext.useSelector((state) => state.value);
  if (state === 'live') {
    return null;
  }
  return <LoadingState label={REGION_LABEL[state]} />;
}
