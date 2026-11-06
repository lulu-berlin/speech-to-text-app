// One MSW network for all tests: setup.ts starts and resets it; tests import it for
// per-test overrides (server.use) that still serve only docs/api/ files. It mocks at the
// fetch and XHR layer rather than MSW 3's socket layer (ADR 0066).
import { FetchInterceptor } from '@mswjs/interceptors/fetch/web';
import { XMLHttpRequestInterceptor } from '@mswjs/interceptors/XMLHttpRequest/web';
import { defineNetwork, InterceptorSource } from 'msw/experimental';
import { handlers } from '../shared/api/handlers';

type Interceptors = ConstructorParameters<typeof InterceptorSource>[0]['interceptors'];

// The web interceptor classes do not type-match InterceptorSource's Node interceptor type
// (separate class declarations with #private fields), so each is cast where it is named:
// the array is typed, and a third entry cannot be added without one.
const interceptors: Interceptors = [
  new FetchInterceptor() as unknown as Interceptors[number],
  new XMLHttpRequestInterceptor() as unknown as Interceptors[number],
];

const network = defineNetwork({
  sources: [new InterceptorSource({ interceptors })],
  handlers,
  context: { quiet: true },
});

// The subset of setupServer's API the tests and setup.ts use.
export const server = {
  use: (...next: Parameters<typeof network.use>) => network.use(...next),
  resetHandlers: () => network.resetHandlers(),
  listen({ onUnhandledFrame = 'warn' }: { onUnhandledFrame?: 'error' | 'warn' | 'bypass' } = {}) {
    network.configure({ onUnhandledFrame });
    network.enable();
  },
  close() {
    network.disable();
  },
};
