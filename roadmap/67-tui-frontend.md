# 67 — A terminal UI frontend

Status: in-progress
Depends on: [66](../docs/specs/local-service-and-frontends.md) (done; the per-user service, frontend
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
- `docs/api` is Markdown and JSON examples of the contract (ADR 0029), and the authoritative wire
  shapes; `docs/api/openapi.yaml` (OpenAPI 3.0.3) is a machine-readable companion for Swagger UI and
  client generation (`docs/backend-reimplementation.md:161-163`). Rust types are mirrored by hand
  from the examples, and the e2e journey is the contract proof.
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

## Decided (workspace, 2026-11-06)

- **The 2026-10-22 client architecture stands, and item 66's client moves onto it** (the user):
  item 66 built one crate, `client/stt-client`, with the library and the `stt-app` and `stt-tray`
  binaries on `ureq` 3 (blocking, no TLS). The user chose reqwest + tokio and separate crates over
  amending this item to the as-built client, so this item migrates it first, as two approved
  refactors before cycle 1: **R1** splits the workspace into `stt-client` (library only),
  `stt-tray` (the tray binary, its `tray-menu` feature and menu) and `stt-app` (the CLI, its own
  crate — the user), moving each binary's tests with it; **R2** replaces `ureq` with async
  `reqwest` (`default-features = false`, plain HTTP on loopback) on tokio, with item 66's tests
  unchanged in what they assert. `stt-tui` is the fourth member and arrives with the first cycle.
  The longer cold build (tokio, hyper, reqwest) is answered by the CI cargo cache (the user).
- **One UI text catalog for every frontend** (the user, 2026-11-06): the user asked that all
  frontends share one source of truth for the text they own, prepared for localization (item 88)
  without assuming English. Recorded as [ADR 0066](../docs/decisions/0066-one-ui-text-catalog-for-every-frontend.md):
  `ui-text/<locale>.json` at the repository root, flat keys with `{name}` placeholders; the browser
  reads it through `t()` (`frontend/src/shared/ui/text.ts`), the Rust clients compile it in
  (`stt-client/build.rs`, `stt_client::text`), with the fallback chain locale → English → key. Item
  67 migrates the TUI's strings and the browser's queue and upload text; the rest of the browser's
  text migrates in a follow-up item the root agent files (new browser strings use the catalog in the
  meantime). Server `message` text stays server-owned and is shown as received.

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

- [x] (basic) With the service running, the TUI attaches with the credential from `config.json` and
      shows the live queue (statuses, progress) — `stt-tui/tests/attach.rs`, `62fe8f25`
- [x] (edge) With no service running, it exits with a message naming `stt-app open` and starts no
      second backend — `stt-tui/tests/no_service.rs`, `ddbf00ef`
- [x] (basic) Upload by path queues a file and it reaches COMPLETED; an invalid path or unsupported
      extension reports an error and the UI stays usable — `stt-tui/tests/upload.rs`, `75c3aca1`
      (the run commit; rebased as `98fb54ed`, only main's `roadmap/README.md` differs)
- [x] (basic) Pause/resume/cancel/retry and reorder work, and the browser sees the same state from
      the shared backend — `stt-tui/tests/commands.rs`, `79856e65`
- [x] (basic) History lists completed tasks; opening one shows the transcript with speaker turns;
      the download command writes the chosen format to the given path, and a bad path reports an
      error — `stt-tui/tests/history.rs`, `af491974`; `stt-tui/tests/download.rs`, `f73e45b0`
- [ ] (basic) Settings edits (device, threads, upload defaults, default frontend, terminal command)
      persist and appear in the browser
- [ ] (basic) The transcript view merges and renames a task's speakers, and the browser shows the
      same speakers after a reload
- [ ] (basic) Two completed tasks can be merged from the TUI, and the merged task appears with its
      transcript
- [x] (edge) SSE updates the view without a refresh, and a dropped stream reconnects and converges
      — `stt-tui/tests/live.rs`, `0317ab9c`
- [ ] (edge) A resize across small and large terminals reflows without crashing, and `NO_COLOR`
      emits no color escapes
- [ ] (e2e) A pty-driven journey in CI against the real service — upload → COMPLETED → transcript —
      with the per-OS legs their runners allow
- [ ] The pinned Rust toolchain builds every path with the committed `Cargo.lock`, and the sync
      check is green
- [ ] (must not break) Browser, window, API contract and the existing e2e suites stay green; the tray
      still lists and opens the TUI
- [ ] (moved from item 66, 2026-11-02; approved as worded 2026-11-06) Opening the TUI from the tray
      opens it in a terminal - PowerShell by default on Windows, Terminal on macOS, the terminal
      chain on Linux - through its registered descriptor, and several TUI instances run side by side
- [ ] (discovered, 2026-11-06) The client reaches the service directly even when an HTTP proxy is
      set in the environment (`HTTP_PROXY`/`ALL_PROXY` without a `NO_PROXY` for the loopback
      address), so the per-install secret is never sent to a proxy and the service stays reachable
- [x] (discovered, 2026-11-06) `q`, Escape and Ctrl-C each quit the TUI and leave the terminal as
      it was (raw mode off, the main screen back), so the user is never trapped (a confirmation may
      come later) — `stt-tui/tests/quit.rs`, `266cee47`
- [ ] (discovered, 2026-11-06) An event stream that stays silent for 15 s - no event, not even the
      service's 5 s heartbeat (ADR 0044) - counts as dropped and is opened anew, as the browser does,
      so a silent network partition does not freeze the view
- [ ] (discovered, 2026-11-06) While the event stream is down the TUI says so (disconnected,
      reconnecting), and the notice goes once a new stream's snapshot arrives
- [ ] (discovered, 2026-11-06) Escape cancels the open upload prompt without quitting and leaves the
      queue as it was
- [ ] (discovered, 2026-11-06) An upload the service refuses (duplicate audio, unsupported format,
      too large) shows the service's message and leaves the UI usable
- [x] (must not break) The TUI's text and the browser's queue and upload text come from the one
      catalog (`ui-text/en.json`): both render its entries, and a missing key shows itself —
      `client/stt-client/tests/text.rs`, `frontend/src/features/queue/QueueView.test.tsx` (the
      "shared UI text" case), `a21afa22`

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
- 2026-11-06 (workspace, before cycle 1; amendment): the 2026-10-22 decisions predate item 66's
  implementation, which built one crate on `ureq`. The user chose (b): keep the three-crate,
  reqwest + tokio architecture and migrate item 66's client to it, with `stt-app` as its own crate
  (four members with `stt-tui`); R1 (crate split) and R2 (reqwest + tokio) approved as refactors
  before cycle 1. See "Decided (workspace, 2026-11-06)". The test-list line moved from item 66 is
  approved as worded and enters the order. Evidence corrected: `docs/api/openapi.yaml` exists as a
  companion to the authoritative examples.
- 2026-11-06 (workspace, cycle 1): refactors R1 (`283e2905`) and R2 (`e02376c7`) done, item 66's 47
  tests unchanged and green on each. Cycle 1, "no service running": red ran on `fc0b7893` with the
  cycle's uncommitted test and an empty `stt-tui` `main` (`crashed: false;` - the TUI exited with
  success, `no_service.rs:59`); green on the same tree plus the `locate` call, committed unchanged as
  `ddbf00ef` (48 tests, fmt and clippy green). No refactoring (the user). Discovered: the client
  sends loopback requests and the secret through an environment `HTTP_PROXY` (appended to the list).
- 2026-11-06 (workspace): rebased onto `main` `0f670d56` at the user's request (main added only the
  roadmap files of items 89, 91-93; no conflict). The hashes above are the pre-rebase run commits;
  their rebased counterparts are R1 `3467f442`, R2 `e3d91fdf`, cycle 1 `fcfbb78c`. Re-run on the
  rebased head `58df03bb`: `cargo fmt --check`, `cargo clippy --locked --all-targets -D warnings` and
  `cargo test --locked` (48 passed) green.
- 2026-11-06 (workspace, cycle 2): "attaches and shows the live queue". Red ran on `cb204fb1` with
  the cycle's uncommitted pty test (`portable-pty` + `vt100`) and cycle 1's `main`: the screen stayed
  empty for 20 s (`terminal/mod.rs:68`). Green on the same tree plus the event-stream client in
  `stt-client` (an SSE parser over the response chunks, the `snapshot` types) and the ratatui queue
  view, committed unchanged as `62fe8f25` (49 tests; fmt and clippy green; the pty test passed 5 of 5
  repeats). No refactoring (the user). Discovered: the TUI cannot be quit; the user asked for `q`,
  Escape and Ctrl-C (appended).
- 2026-11-06 (workspace, cycle 3): "q, Escape and Ctrl-C quit". Red ran on `d911483f` with the
  cycle's uncommitted test: `q did not quit the TUI` (`quit.rs:31`), after the raw-mode check had
  passed. Green on the same tree plus a keyboard thread feeding the terminal loop, committed
  unchanged as `266cee47` (50 tests; fmt and clippy green; 5 of 5 repeats). Refactor approved and
  done: `attach.rs` uses the shared event-stream stand-in (`c5745792`, 50 tests green).
- 2026-11-06 (workspace, cycle 4): "SSE updates and reconnect". Red ran on `7f5963d8` with the
  cycle's uncommitted test: `lecture.m4a` stayed at 42% after a `task-progress` event
  (`terminal/mod.rs:79`). Green on the same tree plus the decoding of `task-updated`,
  `task-progress` and `queue-updated`, their application to the shown snapshot, and a reconnect one
  second after a stream drops (the browser's delay), committed unchanged as `0317ab9c` (51 tests;
  fmt and clippy green; the pty suite 5 of 5 repeats). Refactors approved and done, 51 tests green
  after each: one event-stream stand-in for the TUI tests (`954318f0`), and the TUI's state and view
  split out of `main.rs` (`19f72165`). Discovered and appended (the user): the silent-stream
  watchdog and a visible disconnected state, both from the browser's `subscribeToEvents`.
- 2026-11-06 (workspace, cycle 5): "upload by path". Red ran on `3faf2a89` with the cycle's
  uncommitted pty test: `Upload:` never appeared after `u` (`terminal/mod.rs:79`). Green on the same
  tree plus `stt-client`'s multipart upload (streamed from disk, the contract's extension list
  checked before any request, the service's errors reported) and the TUI's prompt, notice and view
  rows, committed as `75c3aca1` (52 tests; fmt and clippy green). Rebased onto `main` `06c304f1` at
  the user's request (no conflict); re-run on `98fb54ed`: fmt, clippy and 52 tests green. Every hash
  above is from before the two rebases; the current commits are the rebase's: R1 `e4feef0b`, R2
  `c03b510b`, cycle 1 `139b6cfd`, cycle 2 `9de6f62c`, cycle 3 `b9411a2e`, cycle 4 `9b052a31`, cycle 5
  `98fb54ed`, the refactors `857c9a5a`, `4309dc1c`, `9d30a65e`, and the tick/log commits `96bd3364`,
  `3c297351`, `9fc48707`, `ec0a87b1`, `f4733bcc`. Discovered and appended: Escape cancels the upload
  prompt; a refused upload shows the service's message.
- 2026-11-06 (workspace, cycle 6): "pause/resume/cancel/retry and reorder". Red ran on `8bb43d04`
  with the cycle's uncommitted pty test: `no command arrived within 20s`
  (`terminal/mod.rs:238`). Green on the same tree plus `stt-client`'s task and queue commands
  (the contract's transition table mirrored, so a disallowed command sends nothing) and the TUI's
  selection, command keys and retryable rows, committed as `79856e65` (53 tests; fmt and clippy
  green; the pty suite green in 3 repeats). Refactor approved and done: the key map moved to
  `input.rs` (`6fafe1f6`, 53 tests green). The user then asked for one source of truth for UI text
  across the frontends: ADR 0066 and "Decided (workspace, 2026-11-06)" record it, and a test-list
  line was added for it.
- 2026-11-06 (workspace, shared UI text): ADR 0066 recorded (`b1e9ed9c`, with the conventions line
  and this item's amendment). `ui-text/en.json` is the one catalog: `stt-client/build.rs` compiles
  every locale file it finds into constants, `stt_client::text` fills `{name}` placeholders with the
  fallback chain locale → English → key, and the TUI's titles, prompt and notices use it
  (`056a1a7c`). The browser's `t()` (`frontend/src/shared/ui/text.ts`) reads the same file; the
  queue title, uploads and pending lists, pause switch, task verbs and confirmations, upload options
  fields and the fallback banner use it, pinned by the "shared UI text" test (`a21afa22`). Verified:
  Rust 54 tests, fmt and clippy green; `tsc --noEmit`, prettier and `vite build` green; QueueView's
  37 tests green with a 60 s timeout (the default 5 s times out under this sandbox's load, not on
  CI); the full vitest run's other default-timeout failures were the same sandbox slowness and
  passed with 60 s. A missing key shows the key itself, which `stt-client/tests/text.rs` pins.
- 2026-11-06 (workspace, cycle 7): "history and the transcript" (the first half of the history/
  transcript/download line, split by the user). Red ran on `8ac98f92` with the cycle's uncommitted
  pty test: `no GET arrived within 20s` (`terminal/mod.rs:262`). Green on the same tree plus
  `stt-client`'s `history` and `turns` reads (the turns' speakers and turns mirrored from
  `docs/api`) and the TUI's screens - `h` for the completed tasks, Enter for a transcript with each
  turn's speaker, start and text, `b`/Escape back, `q` or Ctrl-C anywhere - committed as `af491974`
  (55 tests; fmt and clippy green). Refactor approved and done: the state and actions moved into
  `Ui` with `open_history`/`open_transcript` (`1de263e2`, 55 tests green). Cycle 7 exposed one bug,
  fixed in the cycle: a `continue` skipped the redraw after queue keys, so the upload prompt only
  appeared with the next event (the upload test caught it).
- 2026-11-06 (workspace, cycle 8): "downloads" (the second half of the history/transcript/download
  line). Red ran on `1de263e2` with the cycle's uncommitted pty test: after `d` the dialog never
  appeared (`terminal/mod.rs:79`). Green on the same tree plus the download dialog (path focused,
  format chosen from the path's extension or cycled with Tab/Left-Right),
  `GET /api/tasks/{id}/transcript?format=…` written to the path after opening it (so a bad path
  sends no request), committed as `f73e45b0` (56 tests; fmt and clippy green). Refactor approved
  and done: the download dialog's state, keys and save moved to `download.rs` (`121869cc`, 56
  tests green).
- 2026-11-06 (workspace): rebased onto `main` `12b07d00` (#108, static-analysis fixes) at the
  user's request. One conflict: `frontend/src/features/queue/TaskRow.tsx`'s imports (main dropped
  the unused `Row`, this branch added `t`), resolved by keeping both changes. The hashes cited above
  are the commits the runs ran on, before the rebase. Gates green on the rebased tree: Rust fmt,
  clippy, 56 tests; frontend `tsc`, prettier, and the queue, history and app vitest files (179
  tests).
