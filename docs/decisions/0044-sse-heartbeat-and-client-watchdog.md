# 0044 — SSE heartbeat event and client inactivity watchdog

Status: accepted, extends the event list of item 12's contract ([ADR 0007](0007-sse-live-updates.md), [ADR 0041](0041-sse-without-ktor-plugin.md)) · Date: 2026-10-11

## Context

Item 21's end-to-end runs found that a silent network partition is never noticed: the TCP connection of
`GET /api/events` stays half-open, `EventSource` fires no `error`, and the app shows "Live" against an unreachable
server indefinitely. Item 29 first planned SSE `:` comment frames as keepalives plus a client watchdog. That
cannot work: the SSE standard ignores comment lines without dispatching any event, so neither browsers nor the
`eventsource` package the tests use let JavaScript see them. A watchdog fed only by real events would treat every
healthy idle stream as dropped and reconnect in a loop.

## Decision

- The server sends a named event `heartbeat` with the payload `{}` ([`docs/api/heartbeat.json`](../api/heartbeat.json))
  whenever a `/api/events` stream has been silent for **5 s**. A stream that carries other events sends no
  heartbeat in between.
- The client treats **15 s** without any event, heartbeats included, as a dropped stream: the connection machine
  goes to `reconnecting`, the stuck `EventSource` is closed and a new one opened, and the first `snapshot` makes
  the state whole (item 12's reconnect rule). 15 s is three heartbeat intervals, so one late or lost heartbeat
  does not trigger a reconnect.
- The heartbeat carries no state; clients apply nothing from it.

## Consequences

- A contract addition: `docs/api/heartbeat.json` and the event list of the HTTP API spec name the new event.
  Clients without a `heartbeat` listener are unaffected, because `EventSource` ignores event names it does not
  listen for.
- Item 21's e2e harness reads `/api/events` and has to skip `heartbeat` frames on idle streams.
- The server also notices a dead subscriber within one heartbeat interval: the heartbeat write hits the closed
  channel and the stream ends quietly (item 29, fix 4).
- Browsers throttle timers in background tabs, which can delay the watchdog. That only delays detection; it does
  not cause a false reconnect, because each received event restarts the watchdog.
