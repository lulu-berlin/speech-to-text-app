# 67 — A terminal UI frontend

Status: spec-approved
Depends on: [66](66-local-service-and-frontends.md) (done; the per-user service, frontend
descriptors and launch model — the credential, runtime endpoint and descriptor this item uses are
built there, as is the Rust pin), [12](../docs/specs/http-api-and-sse.md) (done; the HTTP/SSE
contract)
Blocks: —

## Goal

The app is usable from a terminal: a Rust TUI that attaches to the local service, shows the queue and
history, starts and controls uploads and tasks, reads transcripts with their speaker turns, renames
and merges a task's speakers, merges completed tasks, and changes settings — over the same HTTP/SSE
API the browser uses, with no second processing path. It installs as a frontend, is launched from the
tray's descriptor model in the OS terminal, and works on Linux, macOS and Windows terminals. Audio
playback stays with the browser and window frontends (a terminal cannot play it).

## Evidence

- The backend already serves the whole API (`docs/architecture.md:172-191`): tasks, queue, turns,
  transcript downloads, audio, speakers, settings and the event stream. The browser is one client
  (`frontend/src/shared/api/client.ts`); a TUI is another.
- The frontend is React/Vite with a design system (ADR 0033); a TUI is not React, so it needs its
  own layout and interaction conventions, not a port of the design system.
- Item 66 (spec-approved 2026-10-22) defines the per-user service, the durable `config.json` (port,
  secret), the ephemeral `runtime.json` (pid, endpoint), the frontend descriptors
  (`browser` / `gui` / `terminal`), the one-time-token cookie handshake for the browser, the user's
  default frontend, the standalone Rust tray and the Rust pin (`rust-toolchain.toml`,
  `versions.json`, a committed `Cargo.lock`, a sync check). A TUI registers a `terminal`
  descriptor, reads the durable credential and the running endpoint, and is opened by the tray in
  the OS terminal (Windows PowerShell, macOS Terminal, the Linux `$TERMINAL`/`xdg-terminal-exec`
  chain).
- `docs/api` is Markdown examples of the contract (ADR 0029); there is no machine-readable schema,
  so Rust types are mirrored by hand and the e2e journey is the contract proof.
- The frontend's SSE handling is `new EventSource('/api/events')` with a visible reconnect state;
  the browser sends the credential as a cookie, the TUI must send it as a header (`EventSource`
  cannot, an HTTP client can).
- The e2e harness (item 21) boots the real backend and drives it over HTTP. A TUI check needs a
  pty: the e2e package is Node-only and `node-pty` builds through `node-gyp` → Python, which
  ADR 0002 bans and devenv does not provide.
- Terminal surfaces are text: upload by file path (multipart), queue/history/transcript reading,
  speaker rename/merge, task merging, downloads to a path, settings; audio playback stays with the
  graphical frontends.

## Decided (consultation, 2026-10-20)

- **A TUI is a frontend of the local service**, not a second backend (the user, item 66
  consultation: browser / window / TUI are clients).
- **It is launched from the frontend descriptor model** (item 66) in the OS terminal, and uses the
  service's persisted endpoint and per-install credential.
- **Written in Rust** (the user, 2026-10-20): a standalone binary, not a JVM client. It consumes the
  HTTP/SSE contract (`docs/api`, ADR 0029) and carries no backend or JVM dependency. The Rust
  toolchain and crates are pinned for every path (ADR 0010): development in `devenv.nix`, packaging
  in `flake.nix`, and the non-Nix CI/bootstrap path (Windows included) from one pinned version
  (`versions.json`/`rust-toolchain.toml`), with a committed `Cargo.lock`; the versions stay in sync
  the way `versions-sync` already checks the other pins. Item 66 introduces this pin with the tray.

## Decided (consultation, 2026-10-22)

- **One Cargo workspace, three crates** (the user): `stt-client` (library: `config.json` /
  `runtime.json` discovery, the credential header, task/queue/settings/transcript HTTP, the SSE
  stream parser and reconnect), `stt-tray` (item 66), `stt-tui`. One implementation of the protocol
  and the credential rules for both binaries.
- **ratatui + crossterm** (the user): immediate-mode widgets, no async requirement, the maintained
  standard. The stenographer animation idea does not change this: ratatui's `Canvas` can draw it,
  or libcaca can be used purely as an offline rasterizer whose ANSI output is rendered in a ratatui
  widget — only ratatui owns the terminal, never two libraries at once.
- **reqwest + tokio** (the user): async HTTP with today's most common SSE streaming story. The
  client disables reqwest's default TLS features — the service is plain HTTP on loopback — so the
  static Linux build needs no TLS backend. SSE is parsed from `bytes_stream` in `stt-client`
  (no extra SSE crate), with the browser's backoff/reconnect behavior.
- **Static musl on Linux** (the user): one `x86_64-unknown-linux-musl` binary for the `.deb`, the
  flake package and the bundle, like the ffmpeg/whisper assets; darwin and windows binaries are
  native.
- **A Rust `portable-pty` e2e journey** (the user): the TUI test drives the real binary against a
  real service through a pty (Unix pty / ConPTY), avoiding `node-pty`'s Python build. This is a
  deliberate, recorded deviation from ADR 0046's Playwright e2e wording: Playwright cannot drive a
  terminal app; the journey is the feature's end-to-end proof.
- **Near-full parity** (the user): queue (list, progress, pause/resume/cancel/retry, reorder),
  upload by path, history, transcript with speaker turns, speaker rename and merge, task merging,
  transcripts downloads, and settings. Audio playback is excluded and documented; the browser and
  window keep it.
- **Types are mirrored by hand** from `docs/api` (no schema exists); the pty journey and the
  existing browser e2e are the contract checks.
- **Test list** approved as worded below (13 items, the two parity items included).

## Proposal

1. The `stt-client` crate: discovery from `config.json`/`runtime.json`, authenticated requests
   (secret header), the API types mirrored from `docs/api`, queue/history/transcript/speaker/merge/
   settings calls, and the SSE stream with reconnect.
2. The TUI: ratatui layout for queue, history, transcript (turns and speakers), settings and an
   upload prompt (a path, no file picker); keyboard-driven controls; live updates from SSE; resize
   and `NO_COLOR`; a clear "service not running — run `stt-app open`" state that starts nothing.
3. Transcript actions: speaker rename/merge and merging completed tasks, with the same API calls the
   browser uses.
4. Downloads: choose a format and a target path; report a write error without leaving the UI.
5. Packaging and launch: the static binary per OS, a `terminal` descriptor written by the installer,
   the tray's launch through the configured/default terminal, and the pinned Rust toolchain on every
   path (devenv, flake, non-Nix bootstrap/CI) with the sync check.
6. Docs and ADR: the TUI's interaction conventions, its scope relative to the browser and the
   window (playback excluded), and the pty e2e decision.

## Open questions

None. (All settled 2026-10-22, see "Decided (consultation, 2026-10-22)".)

## Test list (approved; behaviors only)

- [ ] (basic) With the service running, the TUI attaches with the credential from `config.json` and
      shows the live queue (statuses, progress)
- [ ] (edge) With no service running, it exits with a message naming `stt-app open` and starts no
      second backend
- [ ] (basic) Upload by path queues a file and it reaches COMPLETED; an invalid path or unsupported
      extension reports an error and the UI stays usable
- [ ] (basic) Pause/resume/cancel/retry and reorder work, and the browser sees the same state from
      the shared backend
- [ ] (basic) History lists completed tasks; opening one shows the transcript with speaker turns;
      the download command writes the chosen format to the given path, and a bad path reports an
      error
- [ ] (basic) Settings edits (device, threads, upload defaults, default frontend, terminal command)
      persist and appear in the browser
- [ ] (basic) The transcript view merges and renames a task's speakers, and the browser shows the
      same speakers after a reload
- [ ] (basic) Two completed tasks can be merged from the TUI, and the merged task appears with its
      transcript
- [ ] (edge) SSE updates the view without a refresh, and a dropped stream reconnects and converges
- [ ] (edge) A resize across small and large terminals reflows without crashing, and `NO_COLOR`
      emits no color escapes
- [ ] (e2e) A pty-driven journey in CI against the real service — upload → COMPLETED → transcript —
      with the per-OS legs their runners allow
- [ ] The pinned Rust toolchain builds every path with the committed `Cargo.lock`, and the sync
      check is green
- [ ] (must not break) Browser, window, API contract and the existing e2e suites stay green; the tray
      still lists and opens the TUI

## Acceptance criteria

- A TUI client of the service exists on Linux, macOS and Windows, launched as a registered
  frontend, covering the agreed surfaces — queue and reorder, upload, history, transcripts,
  speaker rename/merge, task merging, downloads and settings — with live updates and the service
  credential; audio playback is explicitly out of scope; documented.
- Its pty-driven e2e journey passes in CI; the browser/window frontends and the API contract are
  unchanged; the tray still lists and opens it.
- Every path — `devenv.nix`, `flake.nix` packaging, and the non-Nix CI/bootstrap path, Windows
  included — builds with the same pinned Rust version and the committed `Cargo.lock`, and the sync
  check is green.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-20: filed as a draft at the user's request, as the TUI flavor of item 66's frontend
  model. Not yet discussed; the test list is written at the consultation.
- 2026-10-20 (decision): the TUI is written in Rust (the user) — a standalone binary consuming the
  HTTP/SSE contract, no JVM dependency.
- 2026-10-20 (amendment): the Rust pin covers all three paths — `devenv.nix` (development),
  `flake.nix` (packaging) and the non-Nix CI/bootstrap path, Windows included — from one version
  with a committed `Cargo.lock` and a sync check; the single source is an open question.
- 2026-10-22: consulted (main tree). The user chose the shared `stt-client` crate, ratatui +
  crossterm, reqwest + tokio (TLS off for loopback), static musl, the Rust `portable-pty` e2e and
  near-full parity; the libcaca animation idea is noted as compatible with ratatui. The Rust pin's
  home moved to item 66 (the tray is the first Rust artifact). Test list approved (13 items).
  Status: spec-approved; work starts after item 66 is done.
