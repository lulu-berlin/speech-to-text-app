# 67 — A terminal UI frontend

Status: draft
Depends on: [66](66-local-service-and-frontends.md) (done; the per-user service, frontend
descriptors and launch model — the credential, runtime endpoint and descriptor this item uses are
built there), [12](../docs/specs/http-api-and-sse.md) (done; the HTTP/SSE contract)
Blocks: —

## Goal

The app is usable from a terminal: a Rust TUI that attaches to the local service, shows the queue and
history, starts and controls uploads and tasks, reads transcripts with their speaker turns, and
changes settings — over the same HTTP/SSE API the browser uses, with no second processing path. It
installs as a frontend, is launched from the tray's descriptor model in the OS terminal, and works
on Linux, macOS and Windows terminals.

## Evidence

- The backend already serves the whole API (`docs/architecture.md:172-191`): tasks, queue, turns,
  transcript downloads, audio, speakers, settings and the event stream. The browser is one client
  (`frontend/src/shared/api/client.ts`); a TUI is another.
- The frontend is React/Vite with a design system (ADR 0033); a TUI is not React, so it needs its
  own layout and interaction conventions, not a port of the design system.
- Item 66 (draft) defines the per-user service, the per-install credential, the frontend
  descriptors (`browser` / `gui` / `terminal`), the user's default frontend and the tray. A TUI
  registers a `terminal` descriptor, reads the durable credential and the running endpoint, and can
  be opened by the tray in the OS terminal (Windows PowerShell, macOS Terminal, the Linux
  `$TERMINAL`/`xdg-terminal-exec` chain).
- The e2e harness (item 21) boots the real backend and drives it over HTTP; a TUI check can use the
  same service, and a pty can drive the terminal client.
- Terminal surfaces are text: upload by file path (multipart), queue/history/transcript reading,
  downloads to a path; audio playback stays with the browser and window frontends.

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
  the way `versions-sync` already checks the other pins.

## Proposal (to be settled at the first test item)

1. Rust and a terminal library: ratatui (with crossterm) or cursive; an HTTP client (reqwest or
   ureq) and an SSE parser. Crates are pinned by a committed `Cargo.lock`. The Rust toolchain is
   pinned once and consumed by all three paths (ADR 0010): `devenv.nix` (development),
   `flake.nix` (packaging the binary per system) and the non-Nix CI/bootstrap path, Windows included
   (`versions.json`/`rust-toolchain.toml`); a sync check keeps them equal. MSRV and the Linux
   binary's linkage (static musl vs glibc) are settled in the first cycle. The client's types follow
   `docs/api` (ADR 0029).
2. Surfaces: queue (statuses, progress, pause/resume/cancel/retry, reorder?), history, transcript
   (turns, speakers, downloads), settings; upload by path; live updates from the event stream.
3. Transport and trust: the service credential from the runtime/durable files, SSE reconnect,
   error handling.
4. Packaging and launch: a `terminal` frontend descriptor, per-OS artifacts/installers, terminal
   host selection and the user override.
5. Docs and ADR: the TUI's interaction conventions and its scope relative to the browser and the
   window.

A contract-only prototype can start against the existing server (`nix run .`, item 16) before item
66 lands, but the descriptor, the credential and the tray launch are verified only after 66 is done;
a separate contract-only phase is filed only if the user wants one.

## Open questions

- ratatui vs cursive; the HTTP/SSE crates; the Linux binary's linkage (static musl or glibc);
  whether `docs/api` examples generate client types or are mirrored by hand.
- Which surfaces are in the first version: a minimal queue/history/transcript client or full parity
  (settings, reorder, merges).
- Upload UX (a path argument, no file picker), progress rendering, and where downloads are written.
- Live updates and reconnection; terminal resize and light/dark theme handling.
- Packaging: a separate artifact per OS, its descriptor, and the terminal override.
- e2e: how the TUI is driven in CI (a pty harness) and which journey is its proof.
- The Rust pin's single source (`versions.json` plus `rust-toolchain.toml`) and its sync check; the
  Windows CI/bootstrap install without Nix; the committed `Cargo.lock`.

## Test list (to be written with the user at the consultation)

e2e stance: the item adds an app frontend, so its own e2e journey is required (ADR 0046) — a
pty-driven run against the real backend.

## Acceptance criteria

- A TUI client of the service exists on Linux, macOS and Windows, launched as a registered
  frontend, covering the agreed surfaces, with live updates and the service credential; documented.
- Its e2e journey (upload → completed → transcript) passes in CI; the browser/window frontends and
  the API contract are unchanged.
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
