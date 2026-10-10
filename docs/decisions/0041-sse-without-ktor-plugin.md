# 0041 — SSE without the Ktor plugin

Status: accepted, amends [0007](0007-sse-live-updates.md) (implementation detail) · Date: 2026-10-10

## Context

ADR 0007 chose "the Ktor SSE plugin" for the `/api/events` stream. Ktor's SSE support is the separate
`ktor-server-sse` artifact, which the backend's dependency set does not carry. Adding it means a versions-catalog
entry, a `backend/build.gradle.kts` change, and a `backend/deps.json` (the Gradle→Nix lockfile, ADR 0014/0016)
regeneration — a Nix-level step that only runs on the user's machine — all for one endpoint whose wire format is
a few text lines.

The item 12 contract fixes the payloads (`event:` name → `data:` JSON, blank-line separated, snapshot first),
and the frontend only consumes what the contract names.

## Decision

`GET /api/events` writes the SSE bytes itself over Ktor's core streaming response (`text/event-stream`,
`Cache-Control: no-cache`): each event is `event: <name>\ndata: <json>\n\n`, flushed after every event, with the
`snapshot` as the first event. No new dependency, no Nix lockfile change.

## Consequences

- Items 12 and 15 use `EventSource` against a standard `text/event-stream` exactly as with the plugin; nothing
  at the client changes.
- The endpoint owns its framing (~20 lines), and the per-subscriber overflow handling (item 12's slow-subscriber
  decision) is directly ours to implement, unencumbered by the plugin's loop.
- ADR 0007's intent (SSE push, REST for commands) is unchanged; only its "Ktor SSE plugin" clause is replaced
  by this decision. If a later item needs the plugin's conveniences (multipart events, auto-keepalive), adding
  the artifact is still open and unrelated.