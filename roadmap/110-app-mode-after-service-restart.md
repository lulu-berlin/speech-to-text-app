# 110 — In app mode, a service restart leaves a page that names the way back in

Status: draft
Depends on: [66](../docs/specs/local-service-and-frontends.md) (app mode, the session cookie and the
reopen page), [87](../docs/specs/writes-and-the-connection.md) (the same-page reconnect this scopes
to dev mode)
Blocks: —

## Goal

A browser page left open while the per-user service restarts stops claiming "Reconnecting…"
forever: it lands on the page that says how to enter the app again (the tray, or `stt-app open`),
the same page a bookmark without a session already gets. The dev-mode journey keeps item 87's proof
that a page survives a backend restart and carries on; the app-mode journey proves the way back in
instead, and says why.

## Evidence

- **A restart ends every browser session.** `backend/src/main/kotlin/stt/http/BrowserAccess.kt:18-19`
  says so in the code's own words ("a restart ends every browser session, and the browser then
  enters again through a fresh launch"); `:26` keeps the sessions in an in-memory set, so the
  `stt_session` cookie a page holds is stale the moment the service comes back.
- **The reopen page exists for a page load, not for a running app.** Item 66's discovered line: a
  browser that reaches app mode without the cookie "is shown a page that opens the app from the tray
  or `stt-app open`, not the bare `401 unauthorized` JSON". A running SPA never reloads, so nothing
  takes it there.
- **The event stream's refusal is not a 401.** `backend/src/main/kotlin/stt/http/EventsRoutes.kt:42`
  streams with `ContentType("text", "event-stream")`; an unauthenticated request is refused with the
  JSON error from `Credential.kt:16-17`, which content negotiation cannot render for
  `Accept: text/event-stream`, so the answer is `406 Not Acceptable` with an empty body.
- **Observed 2026-11-15** on item 88's branch, `pnpm --dir e2e test` (app mode; `E2E_BACKEND_CMD`
  unset per `e2e/global-setup.ts:51` and `e2e/playwright.config.ts:36`): spec 87 stops and restarts
  the service on the same port, the page's stale cookie is refused, `GET /api/events` answers `406`
  on every retry (trace `1-trace.network`, 01:52:53–01:52:55), and the page stays on "Reconnecting…"
  until the spec's 60 s wait expires.
- **CI never sees it**: `build.gradle.kts:547` makes `./gradlew e2e` start the dev backend through
  `E2E_BACKEND_CMD`, which has no credential and no session, so 87 passes there.
- **The client cannot read an EventSource status**: the browser exposes only an `error` event and
  `readyState`, so the running app must learn "refused" from a request that can read the status.

## Decided (consultation, 2026-11-15)

- **Keep the trust model** (the user): browser sessions stay per-process; a service restart ends
  them. No session is persisted to disk.
- **The page names the way back in** (the user): after a restart, an open app-mode page shows the
  reopen page rather than reconnecting forever.
- **87 stays the dev-mode proof** (the user): its same-page reconnect keeps running where it is
  meaningful, and the app-mode suite skips it with a recorded reason; an app-mode counterpart proves
  the reopen page instead.
- **A refused event stream is a 401** (the user): credential failures answer 401 (the reopen page
  for the browser), never a content-negotiated 406.

## Proposal

1. **Backend**: a credential failure answers `401` with the browser's own instruction (the reopen
   page for a page load, the JSON error for an API client) whatever the request's `Accept` says —
   the event stream included. No content-negotiated `406` for a missing session.
2. **Frontend**: when the stream closes on a refused request, the app tells "the service is down"
   from "this page may not talk to it" by a small request that can read the status (for example
   `GET /api/settings`): `401` shows the reopen page, a transport failure keeps the reconnect.
   The reopen page says how to enter again and offers to try again.
3. **e2e**: 87 is skipped in app mode with a recorded reason (`E2E_BACKEND_CMD` unset), and an
   app-mode spec restarts the service under an open page and asserts the reopen page, the absence of
   a reconnect loop, and that a fresh launch URL still enters the app.
4. **Docs**: item 87's spec records the dev-mode scope; the reopen page's copy is recorded where the
   app's messages are.

## Open questions

- **How the running app learns `401` from EventSource** (no status is exposed): which request is
  probed, how often, and whether the probe should be `/api/settings`, `/api/health`, or a new
  lightweight endpoint.
- **The reopen page's copy and actions**: exact wording, and whether "try again" reloads the page,
  asks for a fresh launch URL, or both.
- **Which suite owns the app-mode counterpart**: item 87's file or item 66's app-mode file.

## Test list

Draft — written with the user at the consultation, as `- [ ]` behavioral lines.

## Approval log

- 2026-11-15: filed from the 2026-11-15 consultation that followed item 88's verification run: a
  service restart ends the browser's session, the app-mode journey's 87 reconnect cannot succeed,
  and the refused event stream answers 406. The user chose the per-process session model, the reopen
  page after a restart, and a 401 for a refused stream; test list and open questions next.
