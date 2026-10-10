# 0007 — Server-Sent Events for live updates

Status: accepted, SSE implementation amended by [0041](0041-sse-without-ktor-plugin.md) · Date: 2026-10-05

## Context
The UI shows queue state, task status, and progress that change while tasks run. Updates only flow from server to browser; user commands (upload, pause, resume, cancel, reorder) are discrete requests.

## Decision
- Live updates are pushed with **Server-Sent Events** using the Ktor SSE plugin.
- All commands go through REST endpoints.

## Consequences
- One-directional push is sufficient; no WebSocket protocol or message framing is needed.
- The browser's `EventSource` handles reconnection; the frontend must re-fetch state after a reconnect to cover missed events.
- The backend needs an in-process event stream (e.g. a `SharedFlow`) that SSE connections subscribe to.
