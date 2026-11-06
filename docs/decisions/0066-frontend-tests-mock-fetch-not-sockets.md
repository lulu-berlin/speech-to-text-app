# 0066 — The frontend tests mock the API at the fetch and XHR layer, not at MSW 3's socket layer

Status: accepted · Date: 2026-11-06

## Context

MSW 3's `setupServer` intercepts at the socket layer: it wraps Node's `net.Socket` and parses the HTTP
each client writes, so `http.request`, `fetch` and `XMLHttpRequest` all pass through one interceptor.
Every frontend test opens the `/api/events` stream and closes it at cleanup, which aborts a mocked
streaming response. Two defects of that socket layer showed up in this suite with `msw` 3.0.4 and
`@mswjs/interceptors` 0.45.11 (the newest release):

- **An idle connection is passed through to the real network.** After an abort, Undici opens a new
  connection to the same origin and writes to it a tick later. The interceptor sees the client read
  without writing, classifies the connection as a non-HTTP protocol, and passes it through. The next
  mocked request on that connection fails with `ECONNREFUSED` (the tests' origin is `localhost:3000`,
  where nothing listens). This is [mswjs/interceptors#862](https://github.com/mswjs/interceptors/issues/862)
  and [#881](https://github.com/mswjs/interceptors/issues/881); [#879](https://github.com/mswjs/interceptors/issues/879)
  reports the same symptom for this suite's setup (Vitest, jsdom, Node 24.21.0, `ECONNREFUSED
  localhost:3000`) and is closed without a fix.
- **A mocked streaming response is still written after the client aborts it.** The next request on the
  reused connection fails with undici's `SocketError: closed`, and `assert(!this.aborted)` surfaces as
  an unhandled error. This is the abort limitation [#881](https://github.com/mswjs/interceptors/issues/881)
  describes.

Both were reproduced in this repository with the pinned tools. Each one makes the suite fail a
different, unrelated set of tests (19–23 failures of 299 in the first run on 3.0.4). Removing the
read-before-write check with a `pnpm patch` fixed the first defect but not the second.

## Decision

The frontend tests mock at the **fetch and XMLHttpRequest layer**, as MSW 2 did:

- `frontend/src/test/server.ts` builds MSW's network with `defineNetwork` and `InterceptorSource`
  ([`msw/experimental`](https://mswjs.io/api/experimental/define-network)) around the **web builds** of
  `@mswjs/interceptors`' `FetchInterceptor` and `XMLHttpRequestInterceptor`, which patch
  `globalThis.fetch` and `globalThis.XMLHttpRequest` and open no sockets.
- `@mswjs/interceptors` is an exact-pinned devDependency (it is a peer of the web interceptors), and
  `msw` stays the exact-pinned 3.x package that provides the handlers, the responses and the network.
- The test server is a small adapter with the part of `setupServer`'s surface the suite uses
  (`use`, `resetHandlers`, `listen`, `close`, `events`), so the per-test overrides and the unhandled
  policy behave as before.
- The handlers, the `docs/api/` examples and the browser worker (`pnpm dev:mock`) are unchanged: the
  worker intercepts in the browser's service worker, where none of this applies.
- No dependency is patched.

## Consequences

- The suite runs the responses through the same handlers and examples, but not through MSW's Node
  `setupServer`; a regression specific to that entrypoint is not caught here. When the socket-layer
  defects above are fixed upstream, this can be revisited.
- The web interceptors' plain-JavaScript classes do not type-match `InterceptorSource`'s Node
  `Interceptor` type (separate class declarations with `#private` fields), so `src/test/server.ts`
  casts them once. That cast is the only type-level cost.
- Test timing is unchanged from MSW 2: both mock layers leave the suite's existing 5 s test timeout
  as the binding limit on a loaded machine (measured: 2.15.0 and 3.0.4 both time out one or two of the
  heavier transcript tests under a host load average of 20+ on 8 CPUs; both pass when the host is calm).
