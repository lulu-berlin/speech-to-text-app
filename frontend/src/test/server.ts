import { setupServer } from 'msw/node';
import { handlers } from '../shared/api/handlers';

// One MSW server for all tests: setup.ts starts and resets it; tests import it for
// per-test overrides (server.use) that still serve only docs/api/ files.
export const server = setupServer(...handlers);
