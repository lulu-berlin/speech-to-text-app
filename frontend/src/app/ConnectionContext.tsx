import { createActorContext } from '@xstate/react';
import { connectionMachine } from '../shared/state/connectionMachine';

// One connection to GET /api/events for the whole app. The provider is rendered by App,
// so every view and the header read the same live state.
export const ConnectionContext = createActorContext(connectionMachine);
