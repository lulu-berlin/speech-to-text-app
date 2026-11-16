# 67 — A terminal UI frontend

Status: in-progress
Depends on: [66](local-service-and-frontends.md) (done; the per-user service, frontend
descriptors and launch model — the credential, runtime endpoint and descriptor this item uses are
built there, as is the Rust pin), [12](http-api-and-sse.md) (done; the HTTP/SSE
contract)
Blocks: —

## Goal

The app is usable from a terminal: a Rust TUI that attaches to the local service, shows the queue and
history, starts and controls uploads and tasks, reads transcripts with their speaker turns, renames
and merges a task's speakers, and changes settings — over the same HTTP/SSE API the browser uses,
with no second processing path. It installs as a frontend, is launched from the tray's descriptor
model in the OS terminal, and works on Linux, macOS and Windows terminals. It plays a task's audio
through the machine's audio device, as the browser does (the user, 2026-11-07).

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
  speaker rename/merge, downloads to a path, settings; audio playback stays with the graphical
  frontends. *(Superseded 2026-11-07 by the user: the TUI plays audio through the audio device.)*

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
  upload by path, history, transcript with speaker turns, speaker rename and merge, transcripts
  downloads, and settings. Audio playback is excluded and documented; the browser and window keep
  it. *(Superseded 2026-11-07 by the user: playback is in scope, in-process with `rodio`.)*
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
  without assuming English. Recorded as [ADR 0067](../decisions/0067-one-ui-text-catalog-for-every-frontend.md):
  one catalog per locale, flat keys with `{name}` placeholders; the browser reads it through `t()`,
  the Rust clients compile it in (`stt-client/build.rs`, `stt_client::text`), with the fallback
  chain locale → English → key. Item 88 landed first and built the catalogs, checks, review pages
  and generator, so the location is `frontend/src/locales/<locale>.json` (the ADR's 2026-11-15
  amendment) and this item's TUI strings entered them translated, with their review rows. Server
  `message` text stays server-owned and is shown as received.

## Proposal

1. The `stt-client` crate: discovery from `config.json`/`runtime.json`, authenticated requests
   (secret header), the API types mirrored from `docs/api`, queue/history/transcript/speaker/merge/
   settings calls, and the SSE stream with reconnect.
2. The TUI: ratatui layout for queue, history, transcript (turns and speakers), settings and an
   upload prompt (a path, no file picker); keyboard-driven controls; live updates from SSE; resize
   and `NO_COLOR`; a clear "service not running — run `stt-app open`" state that starts nothing.
3. Transcript actions: speaker rename/merge, with the same API calls the browser uses.
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
- [x] (basic) Settings edits (device, threads, upload defaults, default frontend, terminal command)
      persist and appear in the browser - `stt-tui/tests/settings.rs`: threads `b36a1145`; device,
      language and the speaker settings `a87db73c`; default frontend and terminal command `65d155d9`
      (the requests are the browser's own `PUT /api/settings`)
- [x] (basic) The transcript view merges and renames a task's speakers, and the browser shows the
      same speakers after a reload - `stt-tui/tests/speakers.rs`: rename `85b8ffae`, merge
      `704673f5` (the requests are the browser's own calls to the shared backend)
- [x] (edge) SSE updates the view without a refresh, and a dropped stream reconnects and converges
      — `stt-tui/tests/live.rs`, `0317ab9c`
- [x] (edge) A resize across small and large terminals reflows without crashing, and `NO_COLOR`
      emits no color escapes - `stt-tui/tests/layout.rs`, `bfdf87f8` (with `NO_COLOR` the selected
      row is marked `>` and no SGR setting a color or attribute is written)
- [x] (e2e) A pty-driven journey in CI against the real service — upload → COMPLETED → transcript —
      with the per-OS legs their runners allow - `stt-tui/tests/journey.rs` via `./gradlew
      tuiJourney`, in the Linux, macOS and Windows e2e jobs (`c8e75cf2`, `6afc4fe9`); green locally on
      Linux, the three CI legs are verified by the pull request's run
- [x] The pinned Rust toolchain builds the client on the devenv and the non-Nix/CI paths with the
      committed `Cargo.lock`, and the sync check is green (reworded 2026-11-07: the TUI's Nix package,
      which depends on the service package, belongs to item 89) - `cargo build --locked
      --workspace --all-targets` with the devenv's Rust 1.99.0, the CI jobs' rust-toolchain action
      from `versions.json` with `--locked`, `gradle checkVersions` green (Rust 1.99.0)
- [x] (must not break) Browser, window, API contract and the existing e2e suites stay green; the tray
      still lists and opens the TUI - run on the branch: the frontend suite (300 tests), `tsc`, the
      Vite build and prettier; `gradle -p backend check` (ktlint + all backend tests); the docs
      checks (33 tests); the tray's own tests in `cargo test` (`stt-client/tests/tray.rs`); the API
      contract (`docs/api`) is untouched. The Playwright e2e and the window/desktop suites have no
      runner here: the pull request's CI run is their verification.
- [x] (moved from item 66, 2026-11-02; approved as worded 2026-11-06) Opening the TUI from the tray
      opens it in a terminal - PowerShell by default on Windows, Terminal on macOS, the terminal
      chain on Linux - through its registered descriptor, and several TUI instances run side by side -
      the descriptor in the tray's list, the OS-terminal launch from it and `stt-app` starting it are
      item 66's tests (`stt-client/tests/tray.rs`, `open_plan.rs`, `terminal.rs`,
      `stt-app/tests/cli_start.rs`); side by side is `stt-tui/tests/instances.rs`, `f9cf9b55`. The
      real terminal windows of each OS are the user's check on machine with a desktop (CI has no
      display), and writing the descriptor on install is item 89's package.
- [x] (discovered, 2026-11-06) The client reaches the service directly even when an HTTP proxy is
      set in the environment (`HTTP_PROXY`/`ALL_PROXY` without a `NO_PROXY` for the loopback
      address), so the per-install secret is never sent to a proxy and the service stays reachable -
      `stt-tui/tests/proxy.rs`, `54edd99c` (one `no_proxy` client for every call)
- [x] (discovered, 2026-11-06) `q`, Escape and Ctrl-C each quit the TUI and leave the terminal as
      it was (raw mode off, the main screen back), so the user is never trapped (a confirmation may
      come later) — `stt-tui/tests/quit.rs`, `266cee47`
- [x] (discovered, 2026-11-06) An event stream that stays silent for 15 s - no event, not even the
      service's 5 s heartbeat (ADR 0044) - counts as dropped and is opened anew, as the browser does,
      so a silent network partition does not freeze the view - `stt-tui/tests/silence.rs`,
      `4dc738a3`
- [x] (discovered, 2026-11-06) While the event stream is down the TUI says so (disconnected,
      reconnecting), and the notice goes once a new stream's snapshot arrives -
      `stt-tui/tests/connection.rs`, `07bab8d4`
- [x] (discovered, 2026-11-06) Escape cancels the open upload prompt without quitting and leaves the
      queue as it was - `stt-tui/tests/upload.rs`, `05857b01`
- [x] (discovered, 2026-11-06) An upload the service refuses (duplicate audio, unsupported format,
      too large) shows the service's message and leaves the UI usable - `stt-tui/tests/upload.rs`,
      `776aeba4`
- [x] (discovered, 2026-11-06) A speaker rename or merge made in the browser relabels a transcript
      open in the TUI (the `speakers-renamed` / `speakers-merged` events) - `stt-tui/tests/speakers.rs`,
      `96f1cac4`
- [x] (discovered, 2026-11-06) A rename or merge the service refuses (a duplicate or invalid name,
      an invalid merge) shows the service's message and nothing changes - `stt-tui/tests/speakers.rs`,
      `0db6387c`
- [x] (discovered, 2026-11-06) A settings change made in the browser (the `settings-updated`
      event) updates a Settings screen open in the TUI - `stt-tui/tests/settings.rs`, `b060a6f5`
- [x] (discovered, 2026-11-06) Choosing `fixed` without a speaker count asks for one first (or
      keeps the stored mode, as the browser does), so no `invalid-speakers` write is ever sent -
      `stt-tui/tests/settings.rs`, `b03733ac`
- [x] (must not break) The TUI's text and the browser's queue and upload text come from the one
      catalog: both render its entries, and a missing key shows itself —
      `client/stt-client/tests/text.rs`, `frontend/src/features/queue/QueueView.test.tsx` (the
      "shared UI text" case), `a21afa22`; the catalog is `frontend/src/locales/en.json` since the
      merge of item 88 (2026-11-15)

### Parity amendment (approved 2026-11-07)

The user: the TUI must have functionality and UX parity with the GUI, and the screen itself must
teach its use — "the user is not meant to guess what to do". *(Playback, first recorded here as out
of scope, is in scope since the user's correction on 2026-11-07.)*

- [x] (basic) A tab bar shows `1 Queue · 2 History · 3 Settings` and marks the active page; `1`/`2`/`3`
      and `Tab`/`Shift-Tab` switch pages from anywhere - `stt-tui/tests/pages.rs`, `8d31322f`
- [x] (basic) A footer on every screen states that screen's keys in words — reorder, rename/merge
      speakers, download, pause included — so nothing must be guessed, and it names the help key -
      the words are now on screen (`a82ee330`); naming `? help` comes with the next line.
- [x] (basic) `?` opens a full key reference for the current page and closes with `?`/`Esc` -
      `stt-tui/tests/help.rs`, `8210a560` (the footer names it; the reference reuses the footer's words).
- [x] (basic) The queue page shows an upload area above the queue; `Up`/`Down`/`k`/`j` move the
      selection between the upload area and the queue rows and the focused area is marked; `Enter` or
      `Space` on the upload area opens the upload dialog, as `u` does - `stt-tui/tests/upload_area.rs`,
      `8c5664e7` (it opens the existing path prompt; the file browser lands with its own line).
- [x] (basic) Empty states say what to do: the queue says how to upload, history says there are no
      completed tasks, and their keys keep working - `stt-tui/tests/empty.rs`, `24aed003`
- [x] (basic) `u` (or Enter/Space on the upload area) opens a file browser starting in the current
      directory (or `$HOME` when that cannot be read): directories and audio/video files are listed,
      `Up`/`Down` move, `Enter` descends or picks, `Backspace`/`..` goes up, and typing `/` edits a
      path directly; a file the service would refuse by extension is marked and cannot be picked -
      `stt-tui/tests/upload_dialog.rs` (`d1d1b10b`) and `upload_unpickable.rs` (`35e60277`).
- [x] (basic) The upload dialog carries the GUI's fields — language, speakers `auto`/`off`/`fixed`
      with a count, max speakers — starting from the stored defaults (`GET /api/settings`); `Enter`
      sends `file`, `language`, `speakers`, `maxSpeakers` - `stt-tui/tests/upload_options.rs`,
      `ec6d4b52` (the labels are the same catalog keys the browser's panel uses).
- [x] (edge) A path pasted onto the queue page — a terminal's drag-and-drop — opens the upload
      dialog with it: shell-quoted paths and `file://` URIs are recognised, several at once
      are accepted, and text that is not a path is ignored - `stt-tui/tests/paste.rs`,
      `83a4e817`.
- [x] (basic) The mouse does what the keyboard does: clicking a tab switches pages, clicking a
      queue, history or settings row selects it, double-clicking a history row opens its
      transcript, and clicking the queue's upload area opens the upload dialog -
      `stt-tui/tests/mouse.rs`, `7ed6c877`.
- [x] (basic) A transcript longer than the terminal keeps the current segment visible as the cursor
      moves, and scrolls with `PgUp`/`PgDn`, Ctrl-U/Ctrl-D, `Home`/`gg` and `End`/`G`; the
      footer states it - `stt-tui/tests/scrolling.rs`, `fcf17deb` (the line's original
      `Up`/`Down` scrolling became the segment cursor in cycle 42; the wheel split off).
- [x] (edge) The wheel scrolls the focused list — the selection in queue, history and
      settings, the turns in a transcript — as the GUI's wheel does -
      `stt-tui/tests/wheel.rs`, `c882f676`.
- [x] (edge) `fixed` needs a count in 1–20 before Enter sends, and leaving `fixed` sends `auto`; a
      path that cannot be opened is reported with no request - `stt-tui/tests/upload_edge.rs`,
      `eeada046` (the missing-path case also in `upload.rs`).
- [x] (basic) Queue rows show what the GUI's rows show: filename, status, phase while processing,
      detected language, size and progress; FAILED/CANCELED stay reachable -
      `stt-tui/tests/queue_rows.rs`, `1c45c9b2` (size is decimal, `57.7 MB`; the GUI
      renders no size today, so the format is this item's).
- [x] (basic) `d` deletes the selected task after a confirmation (`DELETE /api/tasks/{id}`) and the
      row disappears - `stt-tui/tests/delete_task.rs`, `aaa0ff90` (Delete and Backspace do it
      too, at the user's request; a transcript's `d` still downloads).
- [x] (edge) The device-fallback banner shows above the queue and `x` dismisses it
      (`DELETE /api/settings/device-fallback`), also when there is no fallback -
      `stt-tui/tests/fallback_banner.rs`, `b9f720e1`.
- [x] (basic) `R` resets all merges (`DELETE /api/tasks/{id}/speakers/merges`); names and turns
      return to unmerged - `stt-tui/tests/reset_merges.rs`, `aa33d0e7` (at the user's request
      `R` sends nothing when the turns report no merges).
- [x] (basic) Reassigning marks segments and moves them to the selected speaker, `null` giving a
      new speaker; the turns are read again - `stt-tui/tests/reassign.rs`, `618bb235` (the
      transcript selects a speaker, not a turn, so `a` marks that speaker's segments).
- [x] (basic) Editing a segment's text (`PUT /api/tasks/{id}/segments/{segmentId}` - the contract's
      request; the line first said PATCH) and reverting it (`POST …/revert`) update the transcript -
      `stt-tui/tests/segment_edit.rs`, `8cadcf15`.
- [x] (basic) (discovered, user-directed 2026-11-07: GUI parity) The segment cursor moves with every
      arrow and `h`/`j`/`k`/`l`, and the current segment is highlighted as the browser highlights
      it - `stt-tui/tests/segment_edit.rs`, `8cadcf15`.
- [x] (edge) `segment-edited` and `speakers-reassigned` refresh an open transcript, as the GUI does -
      `stt-tui/tests/events_refresh.rs`, `edf1bf01`.

### Second amendment: look, keys, dialogs and a shared theme (approved 2026-11-07)

The user's review of the in-review TUI: monochrome and dull, tabs only reachable by number, an
upload dialog that does not say what to do and cannot go up a folder, settings edited through a
bottom-row prompt with unclear labels, queue rows that wrap and hide the progress, and a dialog
that leaves clipped remains when it closes. Decisions: [ADR 0068](../decisions/0068-one-theme-token-file-for-every-frontend.md).

- [x] (basic) In a colour terminal the TUI is styled from the shared colour tokens
      (`ui-theme/colors.json`): a coloured tab bar, titled panels, status colours, speaker colours
      and a highlighted selection; `NO_COLOR` still emits no colour codes -
      `stt-tui/tests/theme_colours.rs`, `ca42d749` (the palette is built from the tokens in
      `stt-tui/src/theme.rs`; the mode is the stored `themeMode`, and `auto` reads the OS's
      preference - freedesktop `gsettings` `color-scheme`, macOS `AppleInterfaceStyle`, Windows
      `AppsUseLightTheme` - falling back to dark; the tab bar is accent for the open page and muted
      for the others, panel borders use the border role and their titles accent, statuses take
      their group's colour, speakers their tints, and the selected row the accent tint instead of
      reversed video; `NO_COLOR` keeps the existing test's no-SGR rule).
- [x] (must not break) The browser's light and dark themes read their colours from the same token
      file and render unchanged; the contrast tests stay green over the shared values -
      `frontend/src/shared/theme/tokens.test.ts`, `d35356e0` (the file holds every colour role,
      the five status groups, the speaker palette and the illustration's colours; shadows, being
      elevation rather than a colour role, stay in `theme.ts`, and the contrast and speaker-tint
      tests run over the shared values).
- [x] (basic) The service stores the theme mode: `GET`/`PUT /api/settings` and `settings-updated`
      carry `themeMode` (`auto`, `light`, `dark`; `auto` by default, existing databases included);
      any other value is refused with the settings' validation error -
      `backend/src/test/kotlin/stt/http/SettingsThemeModeSpec.kt` and the upgrade scenario in
      `stt/db/DatabaseSpec.kt`, `51b81fd1` (`V19__theme_mode.sql` - `V18` until the merge of item
      88 renumbered it - fills `auto`; a `PUT` without `themeMode` reads it as `auto`, like the
      other whole-write fields; the browser's form and the Rust client carry the stored mode on
      every write so a change to another setting keeps it).
- [x] (basic) The browser's theme toggle writes the stored mode and follows another frontend's
      change live; before the first settings read it paints the last-known mode -
      `frontend/src/app/App.test.tsx` ("writes the theme mode to the service…"), `03764965` (the
      control waits for the settings to be read, since it writes them whole; localStorage keeps
      the last-known mode for the first paint).
- [x] (basic) In the TUI, `t` cycles `auto → light → dark` and saves it; a change made in the
      browser switches the open TUI live; `auto` follows the OS's preference (freedesktop/GNOME
      `color-scheme`, macOS `AppleInterfaceStyle`, Windows `AppsUseLightTheme`), then the
      terminal's background, then dark - `client/stt-tui/tests/theme_switch.rs` and the unit
      tests in `client/stt-tui/src/theme.rs`, `555497e1` (the next mode is drawn at once, so a
      second `t` goes on from it; a refused write puts the stored mode back).
- [x] (edge) (discovered, the root review 2026-11-15; ADR 0068) In a terminal that does not
      report true colour (`COLORTERM` neither `truecolor` nor `24bit`) the TUI draws every shared
      colour token as its nearest entry (smallest RGB distance) of the 256-colour palette's colour
      cube and grey ramp (16–255; the 16 system colours, which the user's terminal theme
      redefines, are never used); a terminal reporting true colour, and the Windows console
      always, gets the exact 24-bit colour, and `NO_COLOR` still draws none.
      (`client/stt-tui/tests/theme_colours.rs`)
- [x] (e2e) A mode chosen in the browser survives a reload and is what the service reports, and the
      TUI shown against the same service draws in it - `e2e/tests/67-theme.spec.ts` (light and dark:
      the choice reaches `GET /api/settings`, the page paints the mode's background token, and a
      reload paints it again with the header showing the stored answer) and
      `client/stt-tui/tests/journey.rs`'s `the_tui_draws_the_mode_the_service_stored`, run by
      `gradle tuiJourney` (a real service, the whole-settings write the browser makes, the TUI's
      border in the mode's token, then the live repaint when dark is stored from outside).
- [x] (basic) A stenographer placeholder — a 3-row tile at the left of a 3-row header, beside the
      product name and the tabs — and a larger one on the empty queue and history; its dummy
      animation moves while a task is RUNNING and rests on a still frame otherwise (the real artwork
      is the follow-up item's) - `client/stt-tui/tests/stenographer.rs` (the tile is found by its
      rounded corners, which no panel has, so the test does not pin the placeholder's art).
- [x] (basic) (discovered, the user's question 2026-11-09; confirmed 2026-11-09: "browser's rules +
      loading") The large tile follows the browser's rules rather than the running task: on the
      empty queue it always types - nothing runs there, so it is charm, not a running signal (item
      59, `frontend/src/features/queue/QueueView.tsx`) - and on the empty History it rests even
      while a task runs (`EmptyState`); the header's tile keeps typing exactly while a task runs -
      `client/stt-tui/tests/stenographer.rs`
      ("the_large_tile_types_on_the_empty_queue_and_rests_on_the_empty_history").
- [x] (basic) (discovered, the user's question 2026-11-09; confirmed 2026-11-09) While the TUI waits,
      connecting before the first snapshot or loading a transcript, the page shows the resting large
      tile over what it waits for, as the browser's `LoadingState` does (cycle 88: the transcript
      half - `transcript_header.rs`; cycle 100: the connecting half -
      `client/stt-tui/tests/connection.rs`, "the_first_snapshot_is_awaited_with_the_resting_tile_over_connecting").
- [x] (basic) Left/Right and `h`/`l` move between the tabs on Queue and History, `h`/`l` on Settings
      (a transcript keeps them for its segments); each tab underlines its first letter and Shift+`Q`/
      `H`/`S` opens it; `q` still quits and `1`/`2`/`3` still work - `stt-tui/tests/tab_keys.rs`,
      `5de7ec57` (Settings' Left/Right move to the in-row editing line below: they change a value
      until the number rows are edited in place).
- [x] (basic) The upload dialog lists `../` first; `../`, Backspace with no typed path, and Left go
      up a folder, from any folder but the root - `stt-tui/tests/upload_up.rs`, `0643accf`.
- [x] (basic) The upload dialog says what to do — its title, the folder it shows, and a hint line
      (choose a file, Enter opens a folder, go up, type a path) - `stt-tui/tests/upload_guidance.rs`,
      `9bf24844` (two hint lines, so each fits a 60-column terminal; the Space hint of the design
      comes with the multi-file line, which brings marking).
- [x] (basic) A typed folder path (absolute, relative or `~`) and Enter open that folder; a file path
      chooses the file; a path that does not exist is reported inside the dialog -
      `stt-tui/tests/upload_typed.rs`, `3efaa4c5`.
- [x] (basic) Settings with a set of values — device, language, speakers, default frontend, theme —
      open a dropdown listing every option with the current one marked; it floats over the rows
      below it without moving them; Up/Down and Enter choose, and Escape closes the dropdown alone,
      keeping the value — it never quits the TUI - `stt-tui/tests/settings_dropdown.rs`, `b1d652e9`
      (the theme row and its dropdown come with the theme line, which adds the setting).
- [x] (basic) Threads, speaker count and max speakers are edited inside their row (digits,
      Backspace, Up/Down by one), checked against their ranges and saved on Enter; no bottom-row
      prompt remains; Left/Right then move between the tabs on Settings as on the other pages -
      `stt-tui/tests/settings_numbers.rs`, `230c9943`.
- [x] (basic) The terminal command is edited inside its row and starts filled in with the command
      the TUI would open in today (the OS default when none is stored); Enter saves it -
      `stt-tui/tests/settings_terminal.rs`, `2b3061b4` (on Linux the OS default is the words before
      the TUI's command, which is what a stored command is; on Windows and macOS, whose launchers
      no prefix reproduces, the row names PowerShell or Terminal and the edit starts empty).
- [x] (basic) The selected setting shows a one-line description; default frontend and terminal
      command say what they do (shared catalog, so the browser shows the same words) -
      `stt-tui/tests/settings_descriptions.rs`, `72eeaae5` (the keys are new; the browser's Settings
      page shows them when it adopts the catalog, the follow-up item ADR 0067 names).
- [x] (edge) At 80 and at 60 columns a queue row stays on one line with its status and percentage
      visible: the file name is shortened with `…`, and the phase, language and size columns drop
      out as the width shrinks - `stt-tui/tests/queue_narrow.rs`, `8873ca0a`.
- [x] (edge) Closing the upload dialog — Escape, or after an upload — redraws the whole screen, so no
      part of the dialog or the queue stays clipped - `stt-tui/tests/dialog_redraw.rs`, `fff5294b`
      (every overlay: dialogs, confirmations and the key reference).
- [x] (edge) (discovered, the user's report 2026-11-07; confirmed 2026-11-07: "instructions should
      never be clipped") No key hint is ever cut off: a footer that does not fit its row wraps onto
      a second row, each key and its action kept whole, on every page and width -
      `client/stt-tui/tests/footer_wrap.rs` and the packing's unit test in
      `client/stt-tui/src/hints.rs`; the harness wakes on redraws (a condition variable) instead of
      polling a timer.
- [x] (basic) A letter key that occurs in its action is shown by highlighting that letter in place
      (**d**ownload, **r**ename, re**a**ssign); any other key is shown before its action
      (`Enter open`, `Esc close`) - `client/stt-tui/tests/key_hints.rs`, `0f309f4d` (the catalog
      brackets the key, ADR 0067; under `NO_COLOR` the brackets stay; vim keys come second).
- [x] (basic) In a transcript, `Space` plays the task's audio (`GET /api/tasks/{id}/audio` with the
      credential) from the playhead and pauses it; a player bar shows the state, the current and
      total time and the progress - `client/stt-tui/tests/transcript_playback.rs` (cycle 101; the
      clip is the service's FLAC, decoded on the player's thread and written to the default output,
      with `STT_AUDIO_SINK=null` playing it silently in real time where no sound card exists).
- [x] (basic) While playing, the playing segment is highlighted and the cursor follows it until a
      key moves the cursor; `[`/`]` seek back and forward 5 s, and `Enter` seeks to the cursor's
      segment - stopped, Enter on a segment plays from it (the user, 2026-11-08: "otherwise, Enter
      on a segment plays it") - `client/stt-tui/tests/playing_segment.rs`.
- [x] (basic) `p` plays the cursor's segment alone and stops at its end; `p` again stops it -
      `client/stt-tui/tests/segment_playback.rs`.
- [x] (basic) `Space` on a History row plays that task from the start without opening it, the row
      shows it is playing, and `Space` again pauses it - `client/stt-tui/tests/history_playback.rs`.
- [x] (edge) Without an audio device, play shows a "no audio device" notice and the TUI stays
      usable - `client/stt-tui/tests/no_audio_device.rs`.
- [x] (edge) A task without stored audio offers no playback and says why -
      `client/stt-tui/tests/no_audio.rs`.
- [x] (edge) Leaving the transcript or playing another task stops what was playing. (The three
      edge lines were one until cycle 119, which split them word for word to tick this one.) -
      `client/stt-tui/tests/playback_stops.rs`.
- [x] (basic) (discovered in cycle 120, the user 2026-11-12: "add the discovered item") A failed or
      canceled task's status page plays its audio when it has some, as the browser's status page
      shows the player then; without audio it says why, as History's row does.
- [x] (e2e) The journey plays the completed task's real audio on a silent real-time output and the
      playhead advances through its segments.
- [x] (basic) (discovered, the user 2026-11-11: "there's revert, but it should be inside the edit
      dialog too") In a segment's edit prompt, `Ctrl-R` reverts the segment to whisper's text - the
      service's original - and keeps the prompt open with that text, as the browser's editor offers
      the revert there; `v` on the segment still reverts it after the prompt closes.
- [x] (basic) (discovered, the user 2026-11-11: "download should open a dialog like upload where
      folders can be navigated") The download dialog opens like the upload dialog: the current
      folder's subfolders and files listed, `../` and Backspace going up, Enter descending into a
      folder, typing naming the target file, and `Tab` still choosing the format.
- [x] (basic) (discovered, the user 2026-11-11: "while editing, the shortcut keys are disabled, so
      they shouldn't be visible") While a text prompt has the keyboard - a transcript's segment
      edit or speaker rename, History's search - the footer shows only that prompt's keys (`Enter`
      save or search, `Esc` cancel, `Backspace`), not the page's disabled ones.
- [x] (basic) (discovered, the user 2026-11-11: "reset merge doesn't work") `R` on a transcript with
      merges resets them against the real service, and the footer says which reset it is: the
      transcript body's merges, or - on the Speakers panel - the focused speaker's name.
- [x] (basic) (discovered, the user 2026-11-11: "the underline under the shortcut key letters of the
      tab bar should only appear when it's clickable ... the underlined letter should be in the same
      blue colour ... when a dialog is open ... removed") A tab's shortcut letter is underlined in
      the accent role, like the footer's keys, only when that shortcut would act: never on the open
      tab, and never while a dialog, prompt or the help reference holds the keyboard; the active
      tab's bracket stays.
- [x] (basic) (discovered, the user 2026-11-12: "Reset merges shouldn't appear in the shortcuts when
      there are no merges") A transcript without merges offers no `R`eset merges in its footer;
      the hint appears once the service reports merges (`hasMerges`).
- [x] (basic) (discovered, the user 2026-11-12: "what is 'new'?") The transcript footer names `n`
      by what it does - it assigns the marked segments to a new speaker ("new speaker"), the
      browser's New speaker action - not the bare word "new".
- [x] (basic) (discovered, the user 2026-11-12: "Playback shouldn't continue when switching to a
      different tab") Opening a page from a transcript stops what was playing - the player pauses
      and stops walking the cursor; coming back to the task finds it stopped.
- [x] (basic) (discovered, the user 2026-11-12: "'assign' and 'new' should not appear in the footer
      when they can't be used ... make sure that none appear when it cannot be used") A
      transcript's footer shows a key only while it can act: `assign` and `new speaker` only with
      segments marked, Enter's other action - play from the cursor's segment - named while none
      are; `revert` only on an edited segment; `merge` only with two speakers or more; the page,
      start and end keys only with two segments or more.
- [x] (basic) (discovered, the same message) The Speakers panel's footer offers `Reset name` only
      for a speaker whose name is not its default, and names Enter by what it does: rename, or
      assign while segments are marked.
- [x] (basic) (discovered, the same message) The queue's footer offers `pause` or `resume` and
      `cancel` only as the selected task's status allows, `reorder` only for a task in the queue
      order with a neighbour, `queue pause` or `queue resume` as the queue stands and not while the
      upload area has the focus, `on the upload area` only while it does, and `move` only with a
      row; every page offers `theme` once the stored settings are read.
- [x] (basic) (discovered, the same message) History's footer offers `open` and `delete` only with
      a row selected, `retry` only on a failed or canceled one, and `move` only with two rows or
      more.
- [x] (basic) (discovered, the same message) The upload dialog's footer offers `up` only below the
      root, `open folder` only on a folder (or `../`), and `move` only with two rows or more.
- [x] (basic) (discovered, the user 2026-11-12: "The selected segments are not marked in any way.
      It says 'N segment(s) selected' but the user can't see which ones") While segments are
      marked, every segment of the transcript shows a box before it, as the browser's reassign
      mode shows a checkbox: `☑` (accent) on a marked one, `☐` (muted) on the others; the boxes
      go when the last mark does.
- [x] (basic) (discovered, the user 2026-11-12: "it's not obvious that the user needs to go all the
      way up to select a speaker"; the user chose the browser's way the same day) With segments
      marked, Enter opens a "Move N segment(s) to" list - New speaker, then every speaker - as the
      browser's "Move to…" picker: Up and Down choose, Enter moves the marked segments there, Esc
      closes the list with nothing sent; the footer names Enter `move to…` while segments are
      marked, and the Speakers panel's Enter still assigns to its speaker.

### Third amendment: full parity with the browser (approved 2026-11-07)

The user: the TUI must be a fully functional frontend with good UI/UX, not worse than the GUI. An
inventory of the browser (153 behaviours, by surface) against the lines above left these gaps.
The screens are drawn and signed off before the styling cycles.

- [x] (basic) The screens follow the designs the user signed off — `docs/design/tui/`, queue,
      upload dialog, history, transcript with the player, settings with a floating dropdown; dark
      and light - `client/stt-tui/tests/queue_design.rs`, `history_design.rs`, `header_design.rs`,
      `upload_dialog.rs`, `settings_muted.rs`, `turn_time.rs` (the design pass's tests; the user's
      review 2026-11-15: "Perfect.").
- [x] (basic) The tab bar always carries the product name, a connection indicator (Connecting, Live,
      Reconnecting) and the theme mode; a click on the mode cycles it as `t` does (the user,
      2026-11-08: "I'd be happy to have a visible toggle") - `client/stt-tui/tests/header_status.rs`
      (the header's first row, in the browser's words: `● Live`, `◐ Theme: system`).
- [x] (basic) (discovered 2026-11-09; the user chose "derived", 2026-11-09) Which footer keys are
      drawn muted while the stream is not live follows the write rule itself, not a separate list:
      each hint's key is taken as the key press it names and muted exactly when that press would be
      refused - so `r` in History is muted on a FAILED row but not on a COMPLETED one, which has
      nothing to retry - `client/stt-tui/tests/write_gate.rs`
      ("the_footer_follows_the_write_rule_for_the_selected_task"; the completed row's `r` is in the
      accent, the failed row's muted).
- [x] (basic) While the event stream is not live every write is refused in place with the reason,
      as the browser's write gate refuses it (the user chose "both", 2026-11-09): nothing is sent,
      the footer draws the write keys muted while not live, and a write key pressed anyway puts a
      notice naming the refused action and the reason - the browser's "Can't reach the app's
      service" - on the notice line - `client/stt-tui/tests/write_gate.rs` (the muted key's colour
      is compared with an unopened tab's, the live one's with the open tab's).
- [x] (basic) (discovered, the user's decision 2026-11-12: "hide", replacing the muted keys of the
      two lines above) While the stream is not live the footer leaves the write keys out - the same
      write rule picks them - and keeps the keys that still act (moving, searching, pages, help); a
      write key pressed anyway still names the refused action and the reason, and the keys come
      back with the stream - `client/stt-tui/tests/write_gate.rs`.
- [x] (basic) Destructive actions confirm in one dialog style: deleting a task, cancelling a started
      task, merging speakers, reassigning in a task with merges, restoring default settings
      (cycle 82: the dialog and the first four - `client/stt-tui/tests/confirm_dialog.rs`; restoring
      the default settings joins it with its own line, which ticks this one - the user's choice,
      2026-11-09; cycle 141: `settings_restore.rs`).
- [x] (basic) The queue lists QUEUED, RUNNING and PAUSED tasks only; FAILED and CANCELED ones are in
      History, where they are retried, as in the browser - `client/stt-tui/tests/queue_scope.rs`.
- [x] (basic) A running row shows "Transcribing… · 00:12" with the elapsed time ticking, its
      percentage - `client/stt-tui/tests/running_row.rs`.
- [x] (basic) A running row has a thin progress line drawn like the transcript's seek line
      (indeterminate while chunking) - `client/stt-tui/tests/running_progress.rs`.
- [x] (basic) While diarizing, a running row has a second line for the speakers stage with its
      percentage. (The three running-row lines were one until cycle 127, which split them word for
      word - the user, 2026-11-12: "split".) - `client/stt-tui/tests/diarizing_row.rs`.
- [x] (basic) A row offers only the commands its status allows; cancelling a running or started task
      confirms first (cycle 82: the confirmation - `confirm_dialog.rs`).
- [x] (basic) A row moves to the top or the bottom as well as up and down; a refused reorder reverts
      and shows the service's message.
- [x] (basic) The latest five finished tasks show as notices — "transcribed — Enter open" or
      "failed — see History" — each of which opens its target. (Amended in the design pass,
      2026-11-15: the design lists them under a `Finished` heading below the queue's rows, reached
      with Down from the last row.)
- [x] (basic) Several files can be marked in the upload dialog; they upload one after another with
      the chosen options.
- [x] (basic) Each file being uploaded shows its upload progress.
- [x] (basic) A duplicate upload offers to open the existing task -
      `client/stt-tui/tests/duplicate_upload.rs` (a notice line under the queue's rows; Enter opens the
      existing task as the browser's link does).
- [x] (basic) An upload cut off by a lost connection is held and sent again on reconnect. (The four
      upload lines were one until cycle 133, which split them word for word - the user,
      2026-11-12: "split".) - `client/stt-tui/tests/upload_lost.rs`.
- [x] (basic) History lists COMPLETED, FAILED and CANCELED tasks newest first with the status in its
      colour, the finish date, the duration and a stored error - `client/stt-tui/tests/history_rows.rs`
      (newest first by finishedAt, the date and duration as the browser writes them, the error on its
      own line).
- [x] (basic) History follows the events while it is open, as the browser's list does: opening it
      shows the tasks the stream already knows, and one that finishes lands in its rows without the
      page being reopened - `client/stt-tui/tests/history_live.rs` (cycle 98; the page no longer
      reads `GET /api/tasks`, so the journey's History shows the task the run uploaded).
- [x] (basic) `/` searches History by file name and `f` cycles the status filter; when nothing
      matches it says so and one key clears both - Escape, which also closes the search -
      `client/stt-tui/tests/history_search.rs`.
- [x] (basic) A History row retries a FAILED or CANCELED task and deletes any task after a
      confirmation - the retry (`r`) in cycle 83, `client/stt-tui/tests/queue_scope.rs`; the
      delete (Delete or Backspace, the queue's dialog) in cycle 84, `history_delete.rs`.
- [x] (basic) A transcript opens with a header (file name, finish date, duration), shows "Loading
      transcript…" while it loads, and offers a retry when the load fails -
      `client/stt-tui/tests/transcript_header.rs` (Enter on the failed page retries; the loading
      page carries the resting large tile, the transcript half of the waiting-tile line).
- [x] (basic) A FAILED or CANCELED task opens a status page ("Failed while transcribing at 03:12",
      its error, a retry that goes to the queue); a pending task says it is still in the queue -
      `client/stt-tui/tests/status_page.rs` (Enter on a Queue row opens a pending task's page; `r`
      sends the retry and goes to the queue; neither page asks for turns).
- [x] (basic) A transcript's task can be deleted from it after a confirmation, returning to History -
      `client/stt-tui/tests/transcript_delete.rs` (the queue's dialog; Enter sends the DELETE, the
      page returns to History and the event takes the row away).
- [x] (basic) A speaker's name can be reset to its default; a merge confirms by naming the speakers
      folded and the one kept (cycle 82: the merge's confirmation - `confirm_dialog.rs`; cycle 91:
      `client/stt-tui/tests/speaker_reset.rs` - R on the focused speaker sends null, and the panel
      and the turns read `Speaker N` again).
- [x] (basic) Reassigning can mark a whole turn; in a task with merges it confirms first that the
      merges become permanent; Escape leaves reassigning with nothing changed (cycle 82: the
      confirmation - `confirm_dialog.rs`; cycle 92: `client/stt-tui/tests/turn_mark.rs` - `A` marks
      the cursor's turn and `n` moves its segments in one request; cycle 93: the same dialog's
      Escape keeps the selection and sends nothing).
- [x] (edge) A segment edit is trimmed, an unchanged text sends nothing, a refused edit keeps the
      draft with the service's reason, and an empty segment shows `[empty]` -
      `client/stt-tui/tests/segment_edit.rs` (cycles 94-97). The line-breaks clause is the browser's:
      the TUI's prompt is one line and Enter submits, so no newline can enter it (the user's
      decision 2026-11-10).
- [x] (edge) With speakers off, no speaker names or speaker actions show; reading and editing work -
      `client/stt-tui/tests/speakers_off.rs`.
- [x] (basic) During playback, once the cursor is moved away, a key returns it to the playing
      position and following resumes - `f`; `client/stt-tui/tests/follow_playhead.rs`.
- [x] (basic) Settings show the browser's introduction. (The five settings lines were one until
      cycle 142, which split them word for word - the user, 2026-11-13: "five".) -
      `client/stt-tui/tests/settings_intro.rs`.
- [x] (basic) Settings show "Saving…" while a write runs - `client/stt-tui/tests/settings_saving.rs`
      (in the list's title: "Settings · Saving…").
- [x] (basic) (discovered in cycle 144, reading the browser's `settingsMachine`; confirmed
      2026-11-13) Restoring the defaults says "Saving…" too while its write runs, as the
      browser's reset does - `client/stt-tui/tests/settings_restore.rs`.
- [x] (basic) A settings load failure shows on the page with a retry: Settings opens even when
      the read fails, with the error and Enter to read again (the user, 2026-11-13: "page") -
      `client/stt-tui/tests/settings_failure.rs`.
- [x] (basic) Devices read as the browser names them: "Auto", "CPU", and "GPU 0 — <name>" (the
      browser's `deviceLabel`, the index as the service gives it; the user, 2026-11-13:
      "browser") - `client/stt-tui/tests/settings_devices.rs`.
- [x] (basic) Settings show a failed device probe - `client/stt-tui/tests/settings_probe.rs`.
- [x] (edge) A value the service refuses reverts that setting alone with the service's message -
      `client/stt-tui/tests/settings_validation.rs` (the message stands in the setting's row, in
      the danger colour). Amended 2026-11-14: the draft-validation half is gone with the typed
      speaker count - a number row takes digits only, so a draft is never invalid before Enter,
      and the Speakers row chooses its count from its list (the user's one-control decision).
- [x] (basic) Restoring the default settings asks first and then resets every field
      (`DELETE /api/settings`) - `client/stt-tui/tests/settings_restore.rs` (a last row under the
      fields, the user's choice 2026-11-13; its Enter opens the shared confirmation).
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08) The language dropdown
      is a filtering search: typing narrows the list to the languages whose name or code matches,
      as History's search does, and Backspace widens it again. No dropdown moves its cursor with
      `j`/`k` any more - the arrows do - so every letter can be typed -
      `client/stt-tui/tests/settings_language_filter.rs` (the filter's text shows in the dropdown's
      keys line).
- [x] (edge) (discovered in cycle 150; confirmed 2026-11-13) The language dropdown's keys line
      names typing as what narrows it - "[Enter] choose · [type] filter · [Esc] close" - before
      anything is typed, so the search has to be found; the other dropdowns keep "[Enter] choose ·
      [Esc] close" - `client/stt-tui/tests/settings_language_filter.rs`, `settings_dropdown.rs`.
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08) The focused upload
      area is highlighted as a selected task row is - the accent tint across its inside - in
      addition to its `>`; unfocused it carries neither, and under `NO_COLOR` the `>` alone marks it -
      `client/stt-tui/tests/upload_area.rs` (the test reads the tint's colour, not just the text).
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08: "editing a segment
      should have a visible cursor") While a segment is edited the terminal's cursor shows where
      typing goes, and so does every other text prompt - rename, the upload and download paths,
      the terminal command, the number fields; closing the prompt hides it again -
      `client/stt-tui/tests/cursor.rs`, `dad46d2f` (the cursor is a blinking block, the user's
      choice: "like in omp"; the terminal's own shape comes back on exit).
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08) In the download
      dialog Up and Down move between "Path:" and "Format:" as `Tab` does; Left and Right still step
      the format, and the dialog's hint names the arrows - `client/stt-tui/tests/download.rs`
      ("up_and_down_move_between_the_path_and_the_format_as_tab_does"), the dialog's own
      `download.footer` in `ui-text/en.json` (`↑/↓ path / format · ←/→ format · Enter download ·
      Esc cancel`).
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08) In the upload
      dialog's list, Right opens the selected folder as Enter does, the counterpart of Left going
      up; on a file it does nothing (Enter uploads), and the dialog's hint names it -
      `client/stt-tui/tests/upload_guidance.rs`
      ("right_opens_the_selected_folder_and_leaves_a_file_to_enter"); `upload.footer` names
      `→ open folder`.
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08) A two-finger
      horizontal scroll (a trackpad's or touchscreen's, which the terminal reports as left/right
      scroll events) moves between the tabs as Left and Right do; on a transcript it does not move
      the segment cursor - `client/stt-tui/tests/horizontal_wheel.rs`.
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08) A left click on a
      footer hint performs its key, exactly as pressing it does on that page or in the open dialog:
      a hint naming one key (`[p]ause`, `[Enter] open`, `[?] help`) is clickable, and a hint naming
      several (`[↑↓/j/k] move`, `[←→/h/l or Q/H/S] pages`) stays keyboard-only rather than picking
      one of them to guess at - `client/stt-tui/tests/footer_click.rs`.
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08) In a transcript each
      turn's speaker name carries that speaker's tint - the same colour the Speakers panel above
      gives the name - so a turn can be followed down the page by its speaker; `NO_COLOR` writes no
      colour there - `client/stt-tui/tests/turn_tints.rs` (the test compares the name's foreground
      with the panel's own, so it does not repeat the colour tokens).
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08: "the layout should
      be such that turns are easy to recognize") Consecutive turns are separated by one blank row,
      so each turn reads as a block - its speaker, time and text - instead of the turns running on
      as one wall of lines; a turn's continuation lines are indented under its first line -
      `client/stt-tui/tests/turn_spacing.rs`.
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed 2026-11-08, with the short-turn
      choice "own second row") A turn's time sits under its speaker's name instead of beside it:
      the first row is the name, two spaces and the text; the second row is the time one column in,
      two spaces and the text going on; later rows align under the second row's text. A turn whose
      text fits the first row still has the time on a row of its own, so every turn has the same
      shape. The time is drawn in the muted colour, not the text's; `NO_COLOR` writes no colour -
      `client/stt-tui/tests/turn_time.rs` (the time's colour is compared with an unopened tab's).
- [x] (basic) (discovered, the user's report 2026-11-08) A short speaker name does not pull its
      turn's text left: on the name's row the text starts no further left than it does after the
      time, so both rows' text begins in one column (`Bob     Happy to be here.` over ` 00:05`); a
      name wider than the time keeps its two spaces - `client/stt-tui/tests/turn_time.rs`
      ("a_short_name_keeps_its_text_in_the_column_after_the_time").
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed: "fixed is not selectable and
      there's no way to specify a number"; the user chose the combined row over a dropdown of
      counts) "Speakers" and "Speaker count" are one row: its dropdown holds auto, off and fixed,
      and choosing `fixed` - or Enter on a stored fixed row - asks for the count in that row's
      value, so the count is typed at once; a valid count stores `fixed` with it, the other modes
      clear it, Escape leaves the mode as it was. The value reads `fixed · 3`; the separate count
      row is gone, so neither has to be found and paired by hand -
      `client/stt-tui/tests/settings.rs`
      ("choosing_fixed_asks_for_the_count_in_the_speakers_row_and_saves_both").
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed: "default: no terminal found,
      even though my `$TERM` is `xterm-kitty`") On Linux the default terminal is found for the
      terminals people actually run: the chain gains kitty, foot, alacritty, wezterm and ghostty
      (each with the words that make it run a program), after the freedesktop launcher and the
      Debian alternative, so a NixOS niri session with kitty shows `kitty` as the default rather
      than "no terminal found". `$TERM` stays unused - it names a terminfo entry, not a launcher -
      `client/stt-client/tests/terminal.rs` ("the_linux_chain_knows_the_modern_terminals").
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed: "currently it's unclear how to
      use merge and reassign speakers; the workflow should be similar to the GUI") Merging and
      reassigning speakers in a transcript is guided in place in the browser's words: while a
      speaker is being merged a line under the Speakers panel names it and says to go to another
      speaker and press `m` to merge it into that one (`m` cancels); while segments are selected it
      counts them and says to press `Enter` on a speaker to move them there (`a` cancels) - so
      neither flow has to be guessed from the key reference. The line wraps rather than clipping;
      the speaker the segments came from carries `(selected)` -
      `client/stt-tui/tests/mark_guidance.rs`.
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed: "I would rather have `q`
      behave like Escape") `q` walks back exactly as Escape does: it closes the open overlay, then
      a page other than the queue returns to the queue, and only there does it quit - so quitting
      takes as many presses as Escape needs, and no dialog or page is skipped. Ctrl-C keeps
      quitting at once; in a text prompt `q` is still a character, Escape the cancel - and in a
      settings dropdown too, since cycle 150 made it a filtering search (the user, 2026-11-13: "q
      types"; Escape closes the dropdown) - `client/stt-tui/tests/quit.rs`
      ("q_walks_back_page_by_page_as_escape_does_before_it_quits",
      "q_is_a_character_in_a_settings_dropdown_rather_than_escape").
- [x] (basic) (discovered, the user's report 2026-11-08; confirmed: "there is no way to select the
      speakers; the arrow keys should reach the speakers when going up from the edge") A
      transcript's Speakers panel takes the keyboard: Up on the first segment selects the
      bottom-most speaker, Up/Down then move between speakers with the selected row tinted, and
      Down past the last speaker returns to the transcript's first segment; `m` merges, `r` renames
      and `Enter` renames the focused speaker - except while segments are selected (reassigning),
      when `Enter` moves them to it. No speaker row is highlighted while the segments have the
      keyboard, and moving the segment cursor leaves the panel -
      `client/stt-tui/tests/speakers_focus.rs` (the panel row's tint is read from the screen).
- [x] (basic) (discovered after cycle 134, reading `mouse_target`; confirmed 2026-11-13) A click on
      the upload area or on a queue row hits it while finished-task notices or upload lines stand
      above them - `client/stt-tui/tests/mouse.rs`
      ("clicks_hit_the_upload_area_and_the_rows_below_the_notices_and_upload_lines").
- [x] (basic) (discovered in cycle 135; confirmed 2026-11-13) A single file sent with Enter shows
      its upload progress line too, as a marked batch's files do -
      `client/stt-tui/tests/upload_progress.rs`.
- [x] (edge) (discovered after cycle 135, reading the hit-test; confirmed 2026-11-13) A click hits
      the queue row drawn under it after the queue has scrolled to keep the selection in view -
      `client/stt-tui/tests/queue_scroll.rs`.
- [x] (edge) (discovered after cycle 143, reading the settings hit-test; confirmed 2026-11-13) A
      click hits the settings row drawn under it after the list has scrolled on a short terminal -
      `client/stt-tui/tests/settings_scroll.rs`.
- [x] (basic) (discovered, the user's report 2026-11-13: "since the start shortcut key is `g`, the
      hint shouldn't say `gg`. I accept `g` as the new shortcut key") A single `g` jumps to a
      transcript's first segment, and the footer and the key reference name it `Home/g`; the
      scrolling line's `gg` above is read as `g` - `client/stt-tui/tests/transcript_hints.rs`,
      `scrolling.rs`.
- [x] (basic) (discovered, the user's report 2026-11-13: "merge and reassign are either/or";
      browser parity chosen 2026-11-13) Merging and reassigning exclude each other, as in the
      browser: while segments are marked, merging (`m`), renaming a speaker (`r`; the panel's
      Enter moves the segments instead), resetting a speaker's name and resetting the merges (`R`)
      are refused and their hints are gone; while a speaker is marked for a merge, marking
      segments (`a`, `A`) is refused and its hints are gone. Each mode's own key still leaves it -
      `client/stt-tui/tests/merge_reassign.rs`.
- [x] (edge) (discovered after cycle 136, reading the upload lines' key; confirmed 2026-11-13) Two
      uploads with the same file name - from different folders, or a second one sent while the
      first goes - each keep their own progress line -
      `client/stt-tui/tests/same_name_uploads.rs`.
- [x] (basic) (discovered, the user's report 2026-11-13: "clicking on a segment with the mouse
      should select that segment") A left click on a transcript's segment moves the segment cursor
      to it, as the arrows would - `client/stt-tui/tests/segment_click.rs`.
- [x] (edge) (discovered in cycle 139; confirmed 2026-11-13) A click on a segment while a prompt
      or a dialog holds the keyboard - the rename or edit prompt, the "Move to" list, a
      confirmation - leaves the cursor where it is, so a rename in progress still goes to the
      speaker it started on - `client/stt-tui/tests/segment_click_prompt.rs`.

### Discovered in the design pass (2026-11-14)

The user's reports while reviewing the screens by hand; each is one test.

- [x] (basic) (discovered, the user 2026-11-14) Every tab keeps its width when the open page
      changes, so switching pages never moves the titles - `client/stt-tui/tests/pages.rs`.
- [x] (basic) (discovered, the user 2026-11-14) The upload dialog's option chips open their lists
      with Enter or a click: Up/Down move, Enter chooses (the languages' codes and names, or auto,
      off and the counts 1..=20), and Escape closes the list alone -
      `client/stt-tui/tests/upload_options.rs`.
- [x] (basic) (discovered, the user 2026-11-14) A click in the upload dialog selects a file row, a
      double click enters a folder (even while files are marked) or submits a file, and a click
      outside any open dialog closes it - `client/stt-tui/tests/upload_options.rs`.
- [x] (basic) (discovered, the user 2026-11-14) A ticked file's size and date keep their columns
      (the name is padded by the mark's columns, not its bytes) -
      `client/stt-tui/tests/upload_dialog.rs`.
- [x] (basic) (discovered, the user 2026-11-14) A double click on a transcript segment plays it; on
      a Settings row it opens the row's dropdown - `client/stt-tui/tests/segment_click.rs`,
      `client/stt-tui/tests/settings_dropdown.rs`.
- [x] (basic) (discovered, the user 2026-11-14) The key reference lists one hint per row, not a
      run-on line - `client/stt-tui/tests/help.rs`.
- [x] (basic) (discovered, the user 2026-11-14) Every cell's foreground is the theme's text colour
      (white in dark, black in light), never the terminal's default - the frame's base style
      (`client/stt-tui/src/view.rs`).
- [x] (basic) (discovered, the user 2026-11-15) The upload dialog is only as tall as its content
      needs and as wide as the widest thing it draws (its longest entry or its own prose), centred
      horizontally - `client/stt-tui/tests/upload_dialog.rs`.
- [x] (basic) (discovered, the user 2026-11-15) In the upload dialog Tab moves between the files and
      the settings area; inside the settings, left and right pick Language or Speakers, Up returns
      to the files and Down opens the focused chip's list; a chip or its list in focus leaves no
      file highlighted, and the arrows then move the list alone -
      `client/stt-tui/tests/upload_options.rs`.
- [x] (basic) (discovered, the user 2026-11-15) Tab completes a typed path: a folder with its slash,
      several entries to their longest common prefix, one file whole -
      `client/stt-tui/tests/upload_typed.rs`.
- [x] (basic) (discovered in the design pass, approved by the user 2026-11-15) A page's footer is one
      row: its hints whole while they fit, then `? more`, and the `?` reference - sized to its
      entries, in a second column when the frame is short - lists every one left out; a dialog's,
      a prompt's or a panel's list still wraps - `client/stt-tui/tests/footer_wrap.rs`.
- [x] (basic) (discovered, the user 2026-11-15) The Settings page's introduction and the settings'
      names are drawn in the muted colour, as the design draws them; the selected setting's name
      keeps the text colour - `client/stt-tui/tests/settings_muted.rs`.
- [x] (basic) (discovered, the user 2026-11-15) The `?` reference is a table of a `Key` and a
      `Description` column, as btop's help, framed and titled as the upload dialog is: every dialog
      has the same shape; a table taller than the frame scrolls inside it (the user's choice) -
      `client/stt-tui/tests/help_table.rs`.
- [x] (basic) (discovered, the user 2026-11-15) While a dialog is open, the page under it is greyed
      out (drawn in the muted colour) and the dialog's frame is in the accent colour, showing which
      one has the focus, as btop does - `client/stt-tui/tests/dialog_dim.rs`.
- [x] (basic) (discovered, the user 2026-11-15) The `?` reference lists only the keys that act in
      the state the page is in - with nothing marked `Enter play from here` and no `move to…`, with
      a segment marked the other way round - `client/stt-tui/tests/help_table.rs`.
- [x] (basic) (discovered, the user 2026-11-15) The Settings page's `Restore default settings` row
      is muted while it is not selected, as the settings' names are -
      `client/stt-tui/tests/settings_muted.rs`.
- [x] (basic) (discovered, the user 2026-11-15) The `?` reference's keys are centred in their
      column, as btop's are - `client/stt-tui/tests/help_table.rs`.

### Localization (item 88's hand-over, approved 2026-11-15)

Item 88's spec left the TUI's localization to this item once item 88 merged. The user decided
(2026-11-15) that `frontend/src/locales/*.json` is the one UI text catalog for every frontend, and
that the TUI's strings enter it translated, in every shipped locale, with their review records.

- [x] (basic) (item 88's hand-over) The TUI's strings come from the shared catalogs
      (`frontend/src/locales/<locale>.json`) in the App language: the stored `appLanguage` when it
      ships a catalog, otherwise the system's languages resolved as the browser resolves them,
      otherwise English; its dates, times and numbers (finish dates, sizes, durations,
      percentages, plurals) follow that language's format locale (the system's tag when it names
      the same UI locale, else the locale's canonical region), and a stored or system language
      without a catalog falls back as the browser's does - `client/stt-tui/tests/localization.rs`.
- [x] (failure) (discovered, the root review 2026-11-15) With no terminal command stored on
      Windows or macOS, the Settings row names the OS default (PowerShell / Terminal) from the
      shared catalog, never a catalog key. (`client/stt-client/tests/text.rs`,
      `client/stt-tui/tests/settings_terminal.rs`)

### The pull request's CI run (reopened 2026-11-16)

The PR's first CI run (#660, run id 43748398299, head `3da7f225`) failed five jobs, all in the
TUI's pty suite. The user reopened the item for them, lost keys first.

- [x] (failure) (discovered, the PR's CI run 2026-11-16) A key pressed right after a terminal
      resize is applied: 50 rounds of resize-then-`?` and resize-then-Escape each open and close the
      key reference - `client/stt-tui/tests/resize_keys.rs`
- [ ] (must not break) (discovered, the diagnostic run 43795905007) Under a Windows ConPTY the pty
      tests start the TUI and read its first frame: their terminal answers the cursor-position
      query ConPTY asks before the program may start, as every terminal does -
      `client/stt-tui/tests/terminal/mod.rs` (`answer_queries`); proven by the Windows legs of a
      dispatched run
- [x] (edge) (discovered, the diagnostic run on macOS) A typed path longer than its field keeps its
      end - where the cursor is - in view, in the upload and the download dialogs -
      `client/stt-tui/tests/typed_path_tail.rs`
- [x] (edge) (discovered, a local stress run) An upload the service refuses never shows its notice
      beside the upload's own progress line: the line goes first -
      `client/stt-tui/tests/refused_upload_frame.rs`
- [x] (must not break) (discovered, the diagnostic run) The pty tests do not depend on the machine
      they run on: each TUI starts in a folder of its own, the stored theme mode is `dark` unless
      a test stores another, and a folder is compared as the OS resolves it -
      `client/stt-tui/tests/terminal/mod.rs`, `settings_fixture/mod.rs`
- [x] (must not break) (the user, 2026-11-16) Every pty test waits for a key's frame before it
      presses the next key, in `download`, `dialog_redraw` and the journey steps -
      `client/stt-tui/tests/dialog_redraw.rs`, `client/stt-tui/tests/journey.rs`
- [x] (edge) (discovered, the run 43802779592 on macOS) A typed path's error is shown whole inside
      the upload dialog: the dialog widens for it while the terminal has room, and a message wider
      than the terminal keeps its end - what went wrong - in view -
      `client/stt-tui/tests/upload_error_whole.rs`

## Cycle log

Earlier cycles are recorded in the Approval log below; entries from the merge of item 88 on are
kept here.

- 2026-11-15 (workspace, the merge of `main` `f30e7a61`): the branch, based on `17531a56` with 582
  commits, takes `main` (items 88 and 111's roadmap file) by one merge commit instead of a rebase,
  which conflicted at commit 24 on the same files. Resolution, `main` authoritative for the UI copy
  and the settings contract: the settings carry both new fields, `appLanguage` (item 88) then
  `themeMode` (this item), in the backend's `Settings`, repository and JSON shapes, every
  `docs/api/settings*.json` example, the frontend's `Settings` type and `settingsUpdateOf`, and
  their tests. Both branches had added a `V18` migration: `main`'s `V18__app_language.sql` keeps
  its number (released databases carry it) and this item's becomes `V19__theme_mode.sql` (same
  contents, so the same checksum; `DatabaseSpec` counts 19 and lists both checksums;
  `docs/architecture.md` names `V19`). The browser's App nests the theme inside item 88's
  `SettingsLocaleProvider`, with the theme control still waiting for the stored settings. This
  item's routing of the browser's queue and upload text through `t()` from `ui-text/en.json` is
  superseded by item 88's catalogs: `TaskRow`, `QueueView`, `UploadOptionsPanel` and
  `DeviceFallbackBanner` are `main`'s, and `frontend/src/shared/ui/text.ts` is gone. Discovered by
  the TUI's own tests and fixed in the merge: `PUT /api/settings` is a whole write, so the Rust
  `Settings` now carries `appLanguage` and sends it back as stored - without it a theme toggle or a
  settings edit in the TUI reset the user's App language to the system's
  (`header_status.rs`, `theme_switch.rs`, `settings_terminal.rs` and `settings.rs` were red on the
  merge; the TUI settings fixture gained the field, and `settings.rs` expects the `de` that
  `docs/api/settings-updated.json` now stores to travel back).
  Verification on the merge: `gradle check` green; `vitest run` 342 passed and prettier clean;
  `tsc --noEmit` green; the docs package's checks (49 tests, `check-reviews.ts` included) green; the
  e2e lib tests (55), `check-playwright.ts`, prettier and `tsc` green; `cargo fmt --check` and
  `cargo clippy --all-targets -D warnings` green; `cargo test --no-fail-fast` 232 passed and 3
  ignored. `dialog_redraw.rs` failed once in a full run while clippy and another build shared the
  machine and passed 4 of 4 runs alone - a timing flake under load, recorded here, not changed.
- 2026-11-15 (workspace, the localization cycle; `Status: in-review`): the TUI-localization line
  went red at `844bb2fa` with the new `client/stt-tui/tests/localization.rs` and green as
  `67aeb701` with the whole move. `ui-text/` is gone: `client/stt-client/build.rs` compiles
  `frontend/src/locales/<locale>.json` and the shipped UI locales from `docs/api/ui-locales.json`,
  and `stt_client::text`/`set_locale` fill ICU messages (placeholders, plurals, `#`, apostrophe
  quoting, `<tag>` children), format numbers and dates with ICU4X (the browser's medium date,
  decimal comma, the locale's digits), and fall back as the browser does: the stored `appLanguage`
  when it ships a catalog, else the system's languages, else English; the format locale is the
  system's tag when it names the same UI locale, else the locale's canonical region. The TUI calls
  `set_locale` on the startup settings read and on every `settings-updated`, and its sizes,
  dates, durations, percentages and status words go through the same formatting. The TUI's 186
  strings entered all 28 catalogs under `tui.*` (a browser key reused where the meaning is the
  same; the six clashing names renamed), with a review row per key in every locale's page, and the
  pty harness pins `LC_ALL=C.UTF-8` so a developer's own locale does not decide the tests. The
  test-list line was approved as worded at the checkpoint; the refactor checkpoint approved three
  cleanups, committed as `c08e92e4`: one `lookup` helper for `text_in`/`template`, one `Locale`
  literal in `set_locale`, and a `DateFormat` enum instead of a `month_day` bool.
  Verification: `cargo fmt --check`, `cargo clippy --all-targets -D warnings` and
  `cargo test --no-fail-fast` (235 passed, 3 ignored) green; `gradle check` green; `pnpm test`
  green; the docs package's checks (49 tests, `check-reviews.ts` included) green; the e2e lib
  tests and the dev-mode journey green. Handed to the user for review: not pushed, not merged.
- 2026-11-15 (workspace, after the root review; `Status: in-progress`): the root review found the
  catalog move had dropped two keys: `settings.rs`'s default-terminal label still asked for
  `settings.terminal-windows` and `settings.terminal-macos` from the deleted `ui-text/en.json`, so
  on Windows and macOS the row read `default: settings.terminal-windows` and
  `settings_terminal.rs` failed on those CI legs (Linux names the `$TERMINAL -e` prefix, which is
  why the local run stayed green). The discovered line went red with
  `the_default_terminals_are_named_by_the_catalog` in `client/stt-client/tests/text.rs`
  (`template("tui.settings.terminalWindows")` was `None`, expected `Some("PowerShell")`) and green
  with `tui.settings.terminalWindows` (`PowerShell`) and `tui.settings.terminalMacos` (`Terminal`)
  in all 28 catalogs, the same product name in every locale (the user, at the checkpoint, over
  Apple's localized display names), a review row per key on every page (compared `—`, each page's
  neutral audit), the pages regenerated, and `settings.rs` pointed at them. A static sweep of
  every key literal in `client/*/src` (direct `text`/`text_in`/`template` arguments and the match
  arms that pick a key) found no other key missing from `en.json`; no key is built at run time.
  Verification: `text.rs` 4 passed; `settings_terminal.rs` and `localization.rs` green on Linux;
  `cargo fmt --check` green; `check-reviews.ts` 27 pages match; `vitest run` and prettier green.
  The Windows and macOS legs that failed are the proof only CI gives.
- 2026-11-15 (workspace, the 256-colour fallback): ADR 0068 promised the nearest 256-colour entry
  where the terminal reports no true colour, and the TUI drew only `Color::Rgb`; the user chose to
  implement it over amending the ADR, and approved the discovered line with Windows always in 24-bit
  colour and the smallest RGB distance as "nearest". Red:
  `a_terminal_without_true_colour_gets_the_nearest_256_colour_entries` (`theme_colours.rs`, with
  `COLORTERM` empty and the dark mode stored) found the open tab's text as `Rgb(20, 22, 26)`,
  expected `Idx(233)`; the expected entries are derived by hand in the test (background `#14161a`
  → grey 18, entry 233; accent `#7fb2e5` → cube (135, 175, 215), entry 110), and on Windows the
  test expects the exact tokens. Green: `theme::Clues` carries `true_colour` (`COLORTERM`
  `truecolor`/`24bit`, or Windows), and `hex` maps a token to `Color::Indexed(nearest_256(..))`
  without it: the cube's per-channel nearest level against the grey ramp's nearest step, the 16
  system colours never chosen. The pty harness now sets `COLORTERM=truecolor` as it sets
  `LC_ALL`, so every other colour test keeps its 24-bit expectations whatever the shell exports.
  ADR 0068 is amended to the code (the file is `include_str!`'d by `theme.rs`, not compiled by
  `build.rs`; the 256-colour rule).
  Verification: `cargo fmt --check`, `cargo clippy --all-targets -D warnings` and
  `cargo test --no-fail-fast` (237 passed, 3 ignored) green. The Windows leg's 24-bit branch and
  the macOS leg are CI's to prove.
- 2026-11-15 (workspace, the gates after the root review's five findings; head `053f31b4`):
  `cargo fmt --check`, `cargo clippy --all-targets -D warnings` and `cargo test --no-fail-fast`
  (237 passed, 3 ignored) green after the last refactor; `vitest run` (342 passed) and prettier
  green after the catalog change, the frontend's last; `gradle check` green after the KDoc change,
  the backend's last; the docs package's steps (`tsc`, prettier, every check, 49 `node --test`
  tests) green, `generate-reviews.ts` writes nothing and `check-reviews.ts` matches 27 pages; the
  e2e lib tests (55), `check-playwright.ts`, prettier and `tsc` green. The dev-mode journey
  (`E2E_BACKEND_CMD` = the installed backend, run through `bash`): 61 passed, 1 skipped, 2
  failed - `66-launch-token.spec.ts` and `66-settings.spec.ts`, both `spawn …/bin/stt-backend
  ENOENT`: those two specs start the service through `stt-app`, which executes the Gradle start
  script, and this sandbox has no `/bin/sh` for its shebang (the same missing shell kept
  `node_modules/.bin` shims from running, so the steps ran through `node` and the journey's UI
  build through a throwaway wrapper). Not run here, the user's: those two specs on a machine
  with `/bin/sh`, and CI's macOS and Windows `cargo test` legs, the only proof of the
  default-terminal keys and of the Windows 24-bit branch.
- 2026-11-16 (workspace, reopened after the PR's CI run; `Status: in-progress`), cycle "a key
  right after a resize": no crossterm release fixes the mio event source (0.29.0 of 2025-04-05 is
  the latest; master's `event/source/unix/mio.rs` still returns the resize and drops the tty's
  edge-triggered readiness from the same poll). Red: `resize_keys.rs` timed out after 20 s with
  the key reference still open - the Escape pressed after the resize was never read. Green:
  `stt-tui` takes crossterm 0.29 with `use-dev-tty`, which compiles crossterm's other Unix source
  (`event/source/unix/tty.rs`: level-triggered `poll(2)` through `filedescriptor`, `select` on
  macOS, the SIGWINCH pipe drained and the tty polled again on every read); the feature unifies
  with ratatui's crossterm, so there is one crossterm and the keyboard thread is unchanged.
  Windows does not compile either source. Both sources read the same descriptor (crossterm's
  `tty_fd`: stdin when it is a terminal, else `/dev/tty`), so the packaged app and the pty tests
  read where they read before. A throwaway probe (deleted) lost a key within 12 rounds in 6 of 6
  runs on the mio source and none in 6 runs of 400 keys on the `poll(2)` one. The known issue
  "a key pressed in the same moment as a terminal resize can be lost" is removed.
- 2026-11-16 (workspace), the diagnostic run 43795905007 at `812b0141` (a marked diagnostic
  commit printing the terminal's raw bytes on a failing pty test, CI's `cargo test` with
  `--no-fail-fast`): no failure is a lost key, and `resize_keys` passed on Linux and macOS.
  Windows: the TUI runs and its terminal received four bytes, `\x1b[6n` - ConPTY's cursor query
  (`portable-pty` 0.9 creates it with `PSEUDOCONSOLE_INHERIT_CURSOR`), which the harness never
  answered, so the program never started. Linux and macOS: the failures depend on the runner -
  `$HOME` as the dialogs' folder (`portable-pty` starts a command there without a `cwd`), the
  macOS runner's light appearance under `auto`, `/private/var` for `/var`, and typed paths
  longer than their field. A local stress run (12 parallel runs, 8 rounds) failed
  `upload::a_refused_upload…` twice: a frame shows the duplicate's notice beside the 100 % upload
  line, since `send_in_turn` sends `Duplicate` before `UploadDone`.
  Cycle "under ConPTY the TUI starts": red is the run's `Windows (e2e)` leg - `tuiJourney`'s two
  tests that start the TUI waited 60 s on a blank screen with those four bytes; the harness now
  answers `ESC [ 6 n` with `ESC [ 1 ; 1 R`. Green is the next dispatched run's to prove; locally
  the pty suite is unchanged by it.
- 2026-11-16 (workspace), cycle "a typed path keeps its end in view": red - in an 80-column
  terminal the upload dialog's `Or type a path` row ended at `/a-deeply-nested-folder/…/a-deeply`
  at the box's edge, and the cursor stood outside the dialog, so `upload-end.mp3` never showed.
  Green: both dialogs draw the typed text through `draft_tail` (`view.rs`), its last characters
  behind a `…` when it is wider than the row less one cell for the cursor, and put the cursor
  after what they drew.
  Refactor (approved by the user): `typed_field` draws a dialog's typed draft and returns its
  cursor column, for both dialogs (`1ebb8bd2`).
- 2026-11-16 (workspace), cycle "a refused upload's notice never shares a frame with its
  progress line": the harness gains `Tui::frames`, every whole frame replayed from the TUI's
  output, so the test reads each frame and not only the ones a wait saw. Red, 3 of 3 runs: a
  frame held `uploading podcast.mp3 100%` above `≡ podcast.mp3 already uploaded — Enter open`.
  Green: `send_in_turn` sends the file's `UploadDone` before the service's answer (the duplicate
  offer or the notice); an upload whose answer never came is held as before.
- 2026-11-16 (workspace), cycle "the pty tests do not depend on the machine": red, locally, the
  suite run with a light OS (a `gsettings` answering `'prefer-light'` first on `PATH`), a `HOME`
  holding three folders and `TMPDIR` a link to a long folder - `download`, `playing_segment`,
  `settings_dropdown`, `tab_keys` (2), `turn_time` and `upload_guidance` failed as on the macOS
  runner (run 43795905007), the plain run green. The Windows check leg of that run, at `812b0141`,
  failed every pty test with the four bytes `\x1b[6n`, as `Windows (e2e)` did. Green: the
  harness starts a TUI without a folder of its test in an empty one of its own (`portable-pty`
  would start it in `$HOME`); the stand-in answers the startup settings with `themeMode` `dark`
  and the settings tests' fixture stores `dark` (`settings.rs` expects the stored mode written
  back); the 256-colour test waits for the stored mode's chip, as the frames before the startup
  settings land draw in what `auto` resolves to; `upload_guidance` compares the folder resolved
  on Unix (macOS's `/var` is `/private/var`).
  Refactor (approved by the user): one `terminal::settings_in(mode)` replaces the stand-in's
  `stored_dark` and the copies in `theme_colours`, `theme_switch`, `dialog_dim`,
  `header_design`, `help_table` and `history_design` (`377f3b20`); `queue_design`'s `stored()`
  sets four more fields and keeps its own.
- 2026-11-16 (workspace), "every pty test waits for a key's frame before the next key" (a
  hardening, no red): `download` already waits after each key; `dialog_redraw` drops its 100 ms
  sleep before the second `u` - the queue's frame after Escape is awaited, so the two keys
  cannot reach the TUI as one Alt-u - and the journey waits for the typed path's file name before
  Enter sends it.
- 2026-11-16 (workspace), the run 43802779592 at `a9dc8637`: `check (ubuntu-24.04)` and
  `Linux (e2e)` green; `check (macos-15)` 3 failures (from 10). Locally the `stt-tui` suite passed
  10 runs of 10 (187 tests each). Two follow the approved lines and are fixed in their tests,
  as the user approved: `upload::uploading_by_path…` waited for the whole typed path, which a
  long path now shows by its end - it waits for the file name; `upload_area` read the page's
  background from a frame drawn before the startup settings landed (light on the macOS runner)
  - it waits for `Theme: dark`. That window is a known issue now (a light flash at startup for
  `dark` stored on a light OS). The third is a product defect with its own line below. `upload`
  reproduced red locally under a 60-character `TMPDIR`; `upload_area` did not (the frame before
  the settings is brief), its evidence is the macOS run. Both pass under that `TMPDIR`, the light
  OS and plainly.
- 2026-11-16 (workspace), cycle "a typed path's error is shown whole inside the dialog": red - at
  120 columns `/a-folder-that-is-not-there/…/missing.wav does not exist` ended at the dialog's
  border after `…/a-folder-that-is-no`, though the terminal had room (the macOS run's
  `upload_typed` failure, there with its long temporary folder). Green: the dialog's width counts
  the message (`upload_prose_width`), and the message is drawn through `draft_tail`, so one wider
  than the terminal keeps `… does not exist` in view.
- 2026-11-16 (workspace), the journey's `Task 1 has no speaker 1`: the diagnostic in the run
  43802779592 (macOS) read `1 turns; speakers [Speaker 0]`, one segment 0-10360 ms - whisper
  made one segment of `jfk.wav` there, Linux makes two; one segment carries one speaker whatever
  `Count(2)` asks. Not a race (the turns read answers only a completed task) and not the reset.
  Fix (the user's choice): `r_resets_the_merges_the_service_reports` uploads `jfk.wav` said twice
  with 1.5 s of silence between (`twice`, written by the test), asserts at least two speakers
  with the turns in its message before merging the first two it reports, and expects that many
  back after `R`. Green locally (the journey's three tests); macOS and Windows are the next
  dispatched run's.

## Acceptance criteria

- A TUI client of the service exists on Linux, macOS and Windows, launched as a registered
  frontend, with the GUI's functionality and UX parity: a tabbed queue/history/settings layout whose
  screens state their own keys (a footer and a `?` reference), a navigable upload dialog with the
  GUI's upload options, queue rows with the GUI's detail, reorder, delete, the device-fallback
  notice, transcripts with speaker rename/merge/reset, segment reassignment and editing, downloads,
  live updates, audio playback through the audio device and the service credential; documented.
- Its pty-driven e2e journey passes in CI; the window frontend is unchanged; the API contract
  changes only by the stored `themeMode` (ADR 0068); the tray still lists and opens it.
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
  across the frontends: ADR 0067 and "Decided (workspace, 2026-11-06)" record it, and a test-list
  line was added for it.
- 2026-11-06 (workspace, shared UI text): ADR 0067 recorded (`b1e9ed9c`, with the conventions line
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
- 2026-11-06 (workspace, cycle 9): "renaming a speaker" (the first half of the speaker rename/merge
  line; the user chose a speakers list above the turns, `r` to rename and a two-step `m` merge).
  Red ran on `134eb1a1` with the cycle's uncommitted pty test: the transcript showed no `Speakers`
  list (`terminal/mod.rs:79`). Green on the same tree plus `stt-client::rename_speaker`
  (`PUT /api/tasks/{id}/speakers/{speakerId}`, the name trimmed, the bearer secret) and the
  transcript's speakers list, selection and rename prompt; the answer's speakers relabel the turns,
  and Escape closes the prompt with no request. Committed as `85b8ffae` (57 tests; fmt and clippy
  green).
  At the checkpoint the user approved the refactor: the transcript screen's speakers list,
  selection, rename prompt and rename call moved into `transcript.rs`, the screen holding a
  `Transcript` (`2f764e50`, 57 tests green). Two discovered scenarios were confirmed and appended:
  a browser-made rename or merge relabels an open TUI transcript, and a refused rename shows the
  service's message.
- 2026-11-06 (workspace, cycle 10): "merging speakers" (the second half of the rename/merge line).
  Red ran on `bfea2dc2` with the cycle's uncommitted pty test: `m` marked nothing, so
  `Speaker 1 (merging)` never appeared (`terminal/mod.rs:79`); the rename test stayed green. Green
  on the same tree plus `stt-client::merge_speakers` (`POST /api/tasks/{id}/speakers/merge` with
  `{"group":[marked, selected],"into":selected}`, the bearer secret) and the transcript's mark: `m`
  marks, `m` on the marked speaker unmarks with no request, `m` on another merges; the answer's
  speakers relabel the transcript and its turns are read again (a merge joins turns, the
  contract's `speakers-merged` rule). Committed as `704673f5` (58 tests; fmt and clippy green; the
  `stt-tui` suite green in 3 repeats). The rename/merge line is ticked.
- 2026-11-06 (workspace, amendment by the user): "merging completed tasks" / "task merging" was a
  wording mistake that came in with #41 (`549566a9`, 2026-10-23): the app merges a task's
  speakers, never tasks, and has no task-merge route (`docs/api/openapi.yaml`, `SpeakerRoutes.kt`
  holds the only merge). The Goal, Evidence, Decisions, Proposal and Acceptance wording no longer
  names it, and the test-list line "Two completed tasks can be merged from the TUI, and the merged
  task appears with its transcript" is removed; the speaker merge is the ticked rename/merge line.
- 2026-11-06 (workspace, cycle-10 checkpoint): the user approved the refactor — the pty tests
  that start in a transcript (`download.rs`, both `speakers.rs` tests) open it through one
  `terminal::open_transcript` helper; `history.rs`, which tests the opening itself, keeps its own
  steps (`cbe21b36`, 58 tests green). The refused-rename line now also covers a refused merge
  (reworded at the user's request). Next: settings, first half (device, threads, upload defaults).
- 2026-11-06 (workspace, cycle 11): "settings, first half" (the settings line stays unticked). Red
  ran on `4580e7f2` with the cycle's uncommitted pty test: `s` sent nothing (`no GET arrived within
  20s`, `terminal/mod.rs:314`). Green on the same tree plus `stt-client::{settings, save_settings}`
  (`GET`/`PUT /api/settings`, the bearer secret; the write carries the settings whole, the default
  frontend and terminal command included, since the contract reads a missing one as none) and a
  Settings screen: `s` opens it, Up/Down select a row, Left/Right change the threads and save at
  once as the browser does (ADR 0062), and the answer is what it shows. Only the threads change in
  this cycle; the other rows are shown. Item 66's private settings reader was renamed
  `LaunchSettings` to make room for the public type. Committed as `b36a1145` (59 tests; fmt and
  clippy green; the `stt-tui` suite green in 3 repeats).
- 2026-11-06 (workspace, cycle-11 checkpoint): the user approved the refactor — item 66's
  `default_frontend` and `terminal_command_setting` read through `settings()`, and
  `LaunchSettings`/`settings_of` are gone; item 66's two stand-ins serve a full settings body in the
  contract's shape (`05658b8d`, 59 tests green). The `settings-updated` scenario was confirmed and
  appended to the test list. Next: the rest of the settings' first half.
- 2026-11-06 (workspace, cycle 12): "the rest of the settings' first half" (the settings line stays
  unticked). Red ran on `05658b8d` with the cycle's uncommitted pty test: Right on Device sent
  nothing (`no command arrived within 20s`, `terminal/mod.rs:326`). Green on the same tree plus the
  Settings screen stepping the device through the offered devices, the language through the
  browser's own `docs/api/languages.json` (compiled in like the UI text, ADR 0029), the speaker mode
  through auto/off/fixed (a count only with fixed: the other modes write null, as the browser
  does), and speaker count and max speakers within 1..20. Each change is one `PUT /api/settings`;
  committed as `a87db73c` (60 tests; fmt and clippy green; the `stt-tui` suite green in 3 repeats).
- 2026-11-06 (workspace, cycle-12 checkpoint): the user confirmed the discovered line about
  choosing `fixed` without a count (the TUI would send an `invalid-speakers` body today) — recorded
  and fixed after the settings line — and approved the refactor: `open_history`, `open_transcript`
  and `open_settings` share one `fetch` helper (`1673d7ce`, 60 tests green). Next: the default
  frontend and the terminal command, which completes the settings line.
- 2026-11-06 (workspace, cycle 13): "the default frontend and the terminal command" (the settings
  line). Red ran on `a1771fd4` with the cycle's uncommitted pty test: `s` fetched the settings but
  not `GET /api/frontends` (`no GET arrived within 20s`, `terminal/mod.rs:315`). The first green
  attempt exposed two test-side faults, both fixed without weakening an assertion: the older
  settings tests had to answer the frontends read too, and the Down presses were a raw `ESC B`
  rather than the CSI sequence. Green on the same tree plus `stt-client::frontends` and the two
  rows: the default frontend steps through none and the installed ids, and Enter on the terminal
  command opens a prompt holding it whose Enter saves the trimmed command (an emptied one as null,
  the OS launcher). Each change is one `PUT /api/settings`; committed as `65d155d9` (61 tests; fmt
  and clippy green; the `stt-tui` suite green in 3 repeats). The settings line is ticked.
- 2026-11-06 (workspace, cycle 14): "fixed needs a speaker count" (the discovered line). Red ran on
  `a7de980e` with the cycle's uncommitted pty test: stepping to `fixed` with no count sent the
  invalid body and no notice appeared (`terminal/mod.rs:79`). Green on the same tree plus a draft
  speaker count the count row always changes (1..20) and the body carries only while the mode is
  fixed; stepping to fixed without one changes nothing and says "set a speaker count first", as the
  browser keeps the stored mode, and a step that leaves the body as it was sends no request.
  Committed as `b03733ac` (62 tests; fmt and clippy green; the `stt-tui` suite green in 3 repeats;
  `gradle checkVersions` green). The discovered line is ticked.
- 2026-11-06 (workspace, cycle 15): "resize and NO_COLOR" (the edge line). The harness and the app
  were developed together this cycle, so its red was recorded afterwards: with the test and its
  harness in place and `client/stt-tui/src` stashed at `0e1b2dcd`, the test failed with the
  `NO_COLOR` run never showing `> lecture.m4a` (`terminal/mod.rs:125`) - no colour choice existed.
  Green with the harness's extra env vars, `resize` and raw byte capture, and the app's `NO_COLOR`:
  the selected row is marked `>` and no SGR that sets a colour or attribute is written (a reset is
  not one); the settings screen survives 120x24 → 40x12 → 120x24. The harness removes `NO_COLOR`
  from the inherited environment, since this sandbox exports it, so the test decides the choice.
  Committed as `bfdf87f8` (63 tests; fmt and clippy green; the `stt-tui` suite green in 3 repeats).
  The line is ticked. Also run this cycle: `gradle checkVersions` green.
- 2026-11-06 (workspace, cycle 16): "Escape cancels the upload prompt" (the discovered line). The
  behaviour already existed (`CancelPrompt` at the queue, `main.rs`), so there was no red: the new
  pty test is coverage - `u`, a typed path, Escape closes the prompt with no request and the queue
  unchanged, the process stays alive, the prompt opens again, and `q` still quits. Committed as
  `05857b01` (64 tests; fmt and clippy green; the `stt-tui` suite green in 3 repeats). The line is
  ticked.
- 2026-11-06 (workspace, cycle 17): "a refused upload" (the discovered line). Coverage of the
  existing path, so no red: `response_json` surfaces the service's own `message`, and the test drives
  a duplicate audio (`409`, `error.duplicate-audio.json`) and an oversized file (`413`,
  `error.upload-too-large.json`). Each message is shown, no task row is added and the queue stays as
  it was; the TUI still quits normally. Committed as `776aeba4` (65 tests; fmt and clippy green; the
  `stt-tui` suite green in 3 repeats). The line is ticked.
- 2026-11-06 (workspace, cycle 18): "direct loopback despite a proxy" (the discovered line). Red ran
  on `aaab6b33` with the cycle's uncommitted pty test: with `HTTP_PROXY`/`ALL_PROXY` on a closed port
  and no `NO_PROXY`, no event stream opened (`no event stream was opened within 20s`,
  `terminal/mod.rs:409`) - reqwest honoured the proxies. Green on the same tree plus one
  `stt-client::client()` with `.no_proxy()` used by every call (16 call sites, the health probe and
  the launch token included), so the per-install secret never goes to a proxy. The harness clears
  inherited proxy variables so the test sets them. Committed as `54edd99c` (66 tests; fmt and
  clippy green; the `stt-tui` suite green in 3 repeats). The line is ticked.
- 2026-11-07 (workspace, cycle 19): "the silent-stream watchdog" (the discovered line; watchdog
  only, the disconnected notice is its own cycle). Red ran on `a32ef7e8` with the cycle's
  uncommitted pty test: the first stream stayed open and silent, and no second `GET /api/events`
  came (`no event stream was opened within 30s`, `terminal/mod.rs:409`). Green on the same tree
  plus a 15 s timeout around each `next()` of the stream (heartbeats arrive as events, so a live
  stream never trips it); the new stream carries the credential and its snapshot is what the view
  shows. Committed as `4dc738a3` (67 tests; fmt and clippy green; the `stt-tui` suite green in 3
  repeats). The line is ticked. The user asked about mouse support: not in the approved scope (the
  Proposal says keyboard-driven controls); it would need its own spec amendment and test lines.
- 2026-11-07 (workspace, cycle 20): "the disconnected notice" (the discovered line). Red ran on
  `ad1504ec` with the cycle's uncommitted pty test: after the stream dropped nothing was announced
  (`terminal/mod.rs:137`). Green on the same tree plus a `Disconnected` message from the stream
  reader whenever a stream drops, goes silent or cannot be opened, and a line under the screen in
  the browser's words, "Can't reach the app's service. Reconnecting…", added to `ui-text/en.json`
  as `connection.unreachable` (the browser's own literal, `frontend/src/shared/api/client.ts:43`,
  moves to the key in ADR 0067's follow-up migration). The last known queue stays on screen and the
  next stream's snapshot ends the line. Committed as `07bab8d4` (68 tests; fmt and clippy green; the
  `stt-tui` suite green in 3 repeats; the catalog prettier-clean). The line is ticked.
- 2026-11-07 (workspace, cycle 21): "speaker changes made elsewhere" (the discovered line). Red ran
  on `772c0bf3` with the cycle's uncommitted pty test: a `speakers-renamed` event left "Speaker 1"
  on screen (`terminal/mod.rs:137`). Green on the same tree plus `stt-client` reading
  `speakers-renamed` and `speakers-merged` (the task's effective speakers) and the TUI applying them
  to an open transcript of that task only; after a merge it reads the turns again, as the contract
  asks of a client showing them. A merge made in the TUI itself now reads the turns twice (once
  after its own answer, once on the event) - harmless, and kept so a merge still shows while the
  stream is down. Committed as `96f1cac4` (69 tests; fmt and clippy green; the `stt-tui` suite green
  in 3 repeats). The line is ticked.
- 2026-11-07 (workspace, cycle 22): "settings changed elsewhere" (the discovered line). Red ran on
  `b4d04614` with the cycle's uncommitted pty test: a `settings-updated` event left Threads at 4
  (`terminal/mod.rs:137`). Green on the same tree plus `stt-client` reading `settings-updated` and an
  open Settings screen taking it while keeping the device list and GPU names it read (the event
  carries neither); a later change starts from what the browser stored. Committed as `b060a6f5`
  (70 tests; fmt and clippy green; the `stt-tui` suite green in 3 repeats). The line is ticked.
- 2026-11-07 (workspace, cycle 23): "a refused rename or merge" (the discovered line). Coverage of
  the existing error path, so no red: a rename to another speaker's name answered `409
  duplicate-speaker-name` and an invalid merge `400 invalid-merge` (bodies written by hand in the
  contract's error shape; `docs/api` has no example for either). Each shows the service's message,
  both speakers keep their names and turns, and a refused merge reads no turns. Committed as
  `0db6387c` (71 tests; fmt and clippy green; the `stt-tui` suite green in 3 repeats). The line is
  ticked; every behavioural and discovered line is now done.
- 2026-11-07 (workspace, the user): the stenographer animation is **not part of item 67**. The
  2026-10-22 note only recorded that the idea is compatible with ratatui; it has no Proposal step,
  test line or acceptance criterion here. The user asked for a **separate follow-up roadmap item**
  that adds it to the TUI, consulted before it starts (where it shows, `Canvas` or pre-rendered
  frames, a frame timer's cost, reduced motion and `NO_COLOR`, pty tests plus a visual sign-off) -
  for the root agent to file.
- 2026-11-07 (workspace, cycle 24): "the pty e2e journey, Linux leg" (the user chose Linux now,
  macOS and Windows in their own cycle). `client/stt-tui/tests/journey.rs` (ignored by a plain
  `cargo test`) starts the backend's `installDist` launcher with `service` and the tiny model, as
  the Playwright app-mode specs do, uploads `TEST_JFK_WAV` through the real `stt-tui`, waits for
  COMPLETED and reads JFK's words from the transcript. The TUI already did all of it, so there was
  no red: the first runs failed only on the harness (this sandbox has no `/bin/sh`, so the POSIX
  launcher runs through the `sh` on `PATH`). `./gradlew tuiJourney` (root `build.gradle.kts`,
  shaped like `integrationTest`) runs it: green here with `-x prepareRuntime -x
  prepareE2eFixtures` (the devenv provides those), about 6 s. The Linux e2e job runs it after
  `gradlew e2e`; README and AGENTS.md list the task; the docs checks are green (33 tests).
  Committed as `c8e75cf2` (71 tests and the journey ignored; fmt and clippy green). Not ticked:
  the macOS and Windows legs are pending, and the CI run is the user's.
- 2026-11-07 (workspace, the user asked the TUI's minimum size): a throwaway pty probe (not
  committed) drew the queue, the settings and a transcript at 1x1 up to 60x12 - no crash at any
  size, the content clipped below roughly 40x10 (filenames truncated first, since the status and
  progress columns are fixed). No minimum is enforced or announced; that would be a new
  behaviour for the user to decide.
- 2026-11-07 (workspace, cycle 25): "the journey on macOS and Windows". The macOS and Windows e2e
  jobs run `gradlew tuiJourney` after their e2e suites, as Linux does (both already set up the
  pinned Rust for item 66's `stt-app` spec). On Windows the launcher is a `.bat` under `cmd`, which
  starts the JVM as its child, so the journey's cleanup kills the whole process tree (`taskkill
  /T`). Nothing here runs on macOS or Windows, so there was no red and no local proof for those
  legs: the journey is green locally on Linux again (6 s), `actionlint` and the CI docs checks
  (14 tests) are green, and the macOS and Windows legs are the pull request's run to show.
  Committed as `6afc4fe9` (71 tests, the journey ignored; fmt and clippy green). The e2e line is
  ticked with that caveat.
- 2026-11-07 (workspace, the user): **the TUI's Nix package moves to item 89.** The user had chosen
  a flake package here, then pointed out that it needs the service as a dependency. The flake has
  no service package (its `default` is the backend server wrapper; the Rust `stt-app` CLI, the
  `stt-service` runner and the tray are not packaged by Nix), and that package is item 89's
  deliverable (spec-approved; its Nix side splits into a service package and frontend packages
  depending on it). So item 89 builds the TUI package beside the window package - for the root
  agent to amend item 89 - and the pin line here is reworded to the devenv and non-Nix/CI paths.
  Checked on the branch's committed `Cargo.lock`: `cargo build --locked --workspace --all-targets`
  with the devenv's Rust 1.99.0, `client/rust-toolchain.toml` and `versions.json` both 1.99.0,
  `gradle checkVersions` green; the CI client and e2e jobs install that version and build with
  `--locked`. The pin line is ticked. The 2026-10-22 "static musl on Linux" binary is packaging and
  goes with the package to item 89.
- 2026-11-07 (workspace, cycle 26): "several TUIs side by side" (the tray line's automatable half;
  the user approved automating the descriptor, the launch command and the instances here, and
  leaving the real OS windows to their per-OS check). Coverage, so no red:
  `stt-tui/tests/instances.rs` runs two real `stt-tui` processes against one stand-in service - each
  opens its own event stream with the credential, both follow a change, and quitting one leaves the
  other running and answering keys. The descriptor, its OS-terminal launch and `stt-app` starting it
  are item 66's tests; writing the descriptor on install is item 89's package. Committed as
  `f9cf9b55` (72 tests, the journey ignored; fmt and clippy green; the `stt-tui` suite green in 3
  repeats). The tray line is ticked with those two caveats.
- 2026-11-07 (workspace, cycle 27): "must not break" (the last line). Run on the branch: the
  frontend suite (38 files, 300 tests), `tsc --noEmit`, the Vite build and prettier; `gradle -p
  backend check` (ktlint + all backend tests, BUILD SUCCESSFUL); the docs checks (tsc, prettier,
  links, Windows, architecture names and diagram, exceptions, the no-Python check, 33 node tests);
  the tray's own tests in `cargo test`. `docs/api` is untouched. The Playwright e2e and the
  window/desktop suites have no runner in this environment: the pull request's CI run is their
  verification, as it is for the macOS and Windows journey legs. The line is ticked.
- 2026-11-07 (workspace): every test-list line is ticked; `Status: in-review`. Handed to the user
  with the TDD history intact. Left for the root agent / other items: (1) item 89 gains the TUI's
  Nix package depending on the service package, the descriptor written on install and the musl
  static binary; (2) a new roadmap item for the stenographer animation in the TUI; (3) the user's
  verifications: the real OS terminal windows from the tray on each OS, and the pull request's CI
  run (all three journey legs, e2e, desktop).
- 2026-11-07 (workspace, after the user's report): `./gradlew tuiJourney` on the user's NixOS laptop
  failed: the journey's service could not exec Gradle's bytedeco ffmpeg ("Could not start
  dynamically linked executable", NixOS's stub-ld) and the task failed with the transcription. Root
  cause: `appEnv()` puts the managed tools first on the task's PATH (ADR 0047), so the managed
  ffmpeg wins over the devenv's Nix one - the same host limit README documents for `./gradlew e2e`,
  which `tuiJourney` had inherited unhandled. Not a TUI defect: the service and the TUI were
  correct; only the tool it was handed could not run. Fix: the task probes the managed ffmpeg and,
  when it cannot run and PATH has both `ffmpeg` and `whisper-cli` (the devenv's, as the packaged
  app uses on NixOS), runs with those instead; CI's runners run the managed, pinned tools, and
  without a usable PATH pair the task keeps them and warns. Committed as `1c047c14`; README's
  `tuiJourney` bullet says so. Checked here with the fallback path (the sandbox has no managed
  tools): green in 6 s.
- 2026-11-07 (user, NixOS laptop, devenv shell): `./gradlew tuiJourney` green after the fix -
  "the Gradle-managed tools cannot run on this host; using the tools on PATH", the journey's
  upload → COMPLETED → transcript passed in 6.4 s, `BUILD SUCCESSFUL`. That is the Linux leg's
  journey proven on a real NixOS desktop host, not only in the sandbox; the pull request's CI run
  still covers its own Linux runners, macOS and Windows.
- 2026-11-07 (workspace, amendment approved by the user): the item was **reopened** (`in-review` →
  `in-progress`). The user found the TUI below the minimal standard of a usable app on a real host:
  an empty queue with no instructions, no on-screen key map, an upload that is only an empty input
  box, and undiscoverable behaviour (reorder existed but nothing said so). The user's requirement:
  **functionality and UX parity with the GUI**, and the screen itself must teach its use — "the user
  is not meant to guess what to do" — with `?` itself written on screen. Their corrections to the
  first draft: the upload is the TUI's own file browser (type a path **or** navigate directories,
  starting in the current directory, `$HOME` as the fallback), not a GUI; the arrows or `j`/`k` move
  between the queue page's upload area and the queue, and `Enter`/`Space` on the upload area opens
  it; queue reorder must be reachable and visible; the pages mirror the GUI as tabs. The "Parity
  amendment (approved 2026-11-07)" test list (15 lines) records this; work restarts at slice A
  (pages, footer, `?`, upload area focus, empty states).
- 2026-11-07 (workspace, the user): two more amendment lines — **drag-and-drop via bracketed
  paste** (a terminal's file drop arrives as pasted path text: quoted paths and `file://` URIs,
  several at once, anything else ignored; per-terminal, so macOS Terminal.app pastes nothing on a
  drop) and **mouse support** on the same actions (tabs, row selection, the upload area,
  double-click to open a transcript, wheel to scroll), which also requires transcripts to scroll
  at all (keyboard and wheel). Mouse capture is on by default, so terminal text selection needs
  the terminal's own bypass (usually Shift-drag) — to be documented when implemented.
- 2026-11-07 (workspace, cycle 28): the parity amendment's first line, the tab bar. Red ran on
  `080e9e39` with the cycle's uncommitted pty test: no tab bar (`[1 Queue]` never appeared).
  Green on the same tree plus a `Page` enum, page actions handled before any screen sees the key,
  and the bar drawn as the top row with the active page bracketed (so it reads under `NO_COLOR`
  too); a transcript is the History page. Committed as `8d31322f` (73 tests, the journey ignored;
  fmt and clippy green; the `stt-tui` suite green in 3 repeats). Line ticked.
- 2026-11-07 (workspace, cycle 29): the footer line. Red ran on `c85fb957` with the cycle's
  uncommitted pty test: no footer (`u upload` never appeared). Green on the same tree plus a
  one-row footer drawn on every page from `footer.queue`/`footer.history`/`footer.transcript`/
  `footer.settings` in the shared catalog, naming move (`↑↓/j/k`), reorder (`J/K` or
  `Shift-↑↓`), the task and queue commands, upload, open, rename, merge, download, change and
  edit, the page keys, back and quit. It truncates on very narrow terminals; wrapping it is a
  follow-up if the user wants it. Committed as `a82ee330` (74 tests, the journey ignored; fmt and
  clippy green; the `stt-tui` suite green in 3 repeats). Line ticked except the `? help` mention,
  which lands with the `?` reference itself.
- 2026-11-07 (workspace, cycle 30): the `?` line. Red ran on `a1a1fb5a` with the cycle's
  uncommitted pty test: `? help` was not on screen. Green on the same tree plus the `?` action
  (toggled before any page acts, `?` or Escape closes, nothing else acts while open), the footer
  naming it, and an overlay whose body is the page's own footer text - one source, so they cannot
  disagree. Committed as `8210a560` (75 tests, the journey ignored; fmt and clippy green; the
  `stt-tui` suite green in 3 repeats). Line ticked.
- 2026-11-07 (workspace, cycle 31): the upload-area line. Red ran on `8210a560` with the cycle's
  uncommitted pty test: no upload area. Green on the same tree plus a three-row upload area above
  the queue's table, focus moving between it and the rows (`Up`/`k`, `Down`/`j`), Enter or Space
  opening the prompt while it is focused, and task/reorder keys ignored and the row highlight off
  then. Committed as `8c5664e7` (76 tests, the journey ignored; fmt and clippy green; the `stt-tui`
  suite green in 3 repeats). Line ticked for the focus part; the file browser is next.
- 2026-11-07 (workspace, cycle 32): the empty-state line. Red ran on `30bb04ab` with the cycle's
  uncommitted pty test: an empty frame (`No tasks yet` never appeared). Green on the same tree plus
  `queue.empty` in the catalog and an empty-state paragraph in place of the queue and history
  tables, naming the key and the upload area. Committed as `24aed003` (77 tests, the journey
  ignored; fmt and clippy green; the `stt-tui` suite green in 3 repeats). Line ticked.
- 2026-11-07 (workspace, cycle 33): the upload dialog's browser. The user chose our own
  implementation over `tui-file-explorer`/`ratatui-explorer` (kept as reference only). Red was
  recorded against the pre-cycle source (`git stash` of `client/stt-tui/src`, run on `9dae893f`):
  the test waited for the browser's `clips/` and timed out - the old bare prompt had no list.
  Green with `client/stt-tui/src/upload_dialog.rs` (browse, dirs first, hidden skipped, typed
  path), the harness's `start_in` working directory, the dialog mode in `input.rs` (Up/Down move,
  letters type - a path containing j/k must not jump), the modal list and its own footer, and the
  old prompt replaced by it; `stt-tui/tests/upload.rs` still passes unchanged through the dialog.
  Committed as `d1d1b10b` (78 tests, the journey ignored; fmt and clippy green; the `stt-tui` suite
  green in 3 repeats). The amendment's browser line stays unticked for its two remaining parts:
  unsupported files marked as unpickable, and the `/` typed-path affordance spelled out; the
  option fields (language, speakers, max speakers) are the next line.
- 2026-11-07 (workspace, refactor checkpoint after the upload dialog's browser): the user approved all
  three suggestions, each its own commit with the suite unchanged (78 tests, fmt and clippy green):
  `fcae2414` lets `UploadDialog::handle` take its own keys (the `Download::handle` shape), leaving
  `main::queue_action` one early branch and no upload arms; `f2bf17f3` replaces
  `input::action`'s three booleans with one `Mode { Normal, Text, Download, Upload }`, so an
  impossible combination cannot be expressed (an arm-ordering bug came from exactly that); and
  `7bc146f5` draws the help reference and the upload dialog through one `modal` helper. Not
  suggested: a `terminal::session()` test helper (would touch ~20 passing tests for no behavioural
  gain). Next: the dialog's option fields.
- 2026-11-07 (workspace, cycle 34): the option-fields line. Red was not recorded first this time
  (the dialog and its fields were written together); the first run failed because the test's
  fixture answered `docs/api/settings.json`, whose defaults *are* auto/8, so nothing seemed to
  change - the code was right, the fixture wrong; it now answers a settings body with de/fixed 3/
  12. Green with the dialog's `Language/Speakers/Speaker count/Max speakers` rows (labels from the
  shared catalog keys the browser's upload panel uses, wire values as the browser sends them),
  `GET /api/settings` filling them when the dialog opens, Tab/Shift-Tab between the list and the
  fields, Left/Right adjusting within the contract's ranges, and `stt-client::upload` writing
  `language`, `speakers` and `maxSpeakers` as multipart fields. Committed as `ec6d4b52` (79 tests,
  the journey ignored; fmt and clippy green; the `stt-tui` suite green in 3 repeats). Line
  ticked; the edge line (leaving fixed sends auto, an unopenable path sends nothing) is next.
- 2026-11-07 (workspace, cycles 35-36): the upload dialog's edge and unpickable-file lines.
  Cycle 35 (`eeada046`) covers the edges without a red (the behaviour was in place): leaving
  fixed sends `auto`, the count clamps at 1, and a missing path is reported with no request.
  Cycle 36 (`35e60277`) marks non-audio entries `(not audio)` and refuses them with the
  service's wording before any request, the dialog staying open. During 35 a wrong assertion I
  copied (Speakers: 3 after clamping) failed the suite; fixed and the test commit amended. With
  these, the amendment's upload lines (browser, options, edge) are complete; next are the queue
  row detail, delete, the fallback banner, then the transcript surfaces.
- 2026-11-07 (workspace, refactor checkpoint after the upload dialog): the user approved all three,
  each its own commit, the suite unchanged (81 tests, fmt and clippy green): `d4d6a317` moves
  `step_choice`/`step_between`/`language_codes` into `choices.rs`, shared by the Settings screen and
  the upload dialog (the dialog had its own clamping and language lookup); `7da96ba6` puts the
  multipart field reader in `tests/terminal/mod.rs` for the two upload tests that had copied it;
  `c3a123e1` splits the option fields into `upload_options.rs` (Field, its catalog labels, the
  values, the ranges, the request options), leaving `upload_dialog.rs` 242 lines of browser and key
  handling (from 373 across the two). Not suggested: moving main.rs's page fetchers (one call site
  each) and a `terminal::session()` helper (touches ~20 passing tests for no behavioural gain).
  Next: the queue-row detail, delete and the device-fallback banner.
- 2026-11-07 (workspace, cycle 37): the queue-row line. Red ran on `a1440469` with the cycle's
  uncommitted pty test: no phase in a row. Green with `Task`'s `phase`, `language` and
  `sizeBytes` (defaulted for older fixtures), six columns under a catalog-labelled header, the
  phase from the shared catalog only while RUNNING, the size in decimal units, and the language
  shown or `-`. The suite's existing NO_COLOR test caught two bugs this landing: a FAILED task
  showing a stale phase, and a bold header emitting SGR under NO_COLOR - both fixed before the
  commit. Committed as `1c45c9b2` (82 tests, the journey ignored; fmt and clippy green). Line
  ticked.
- 2026-11-07 (workspace, cycle 38): the delete line. Red ran on `a81daa87` with the cycle's
  uncommitted pty test: no confirmation appeared. Green with `stt-client::delete_task`,
  `Event::TaskDeleted` (the contract's `task-deleted`) removing the task from the snapshot and
  the queue, a `Confirm` input mode (Enter/Escape only), the confirmation modal naming the file,
  and `d`/Delete/Backspace on the queue (a transcript's `d` still downloads). The test's first
  attempt waited for the row to vanish by matching the whole screen, which the notice "deleted
  memo.wav" kept true; it now checks queue rows only. Committed as `aaa0ff90` (83 tests, the
  journey ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 39): the device-fallback line. Red ran on `8c32f39e` with the
  cycle's uncommitted pty test: no startup settings read. Green with the startup
  `GET /api/settings`, `settings-updated` keeping the notice current, the banner above the
  queue, `x` sending the DELETE and its answer clearing it (nothing without a banner), and
  `Settings::device_fallback` as a read-only field (`skip_serializing`, so PUT bodies are
  unchanged). The startup read made every test see an extra settings GET, so the pty stand-in
  now answers `GET /api/settings` with the contract's defaults and hands the next one to a test
  only on `capture_next_settings()`; the settings, upload, pages, layout and fallback tests were
  updated, two stale constants removed. Committed as `b9f720e1` (84 tests, the journey ignored;
  fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 40): the reset-merges line. Red ran on `dd9153a2` with the
  cycle's uncommitted pty test: `R` did nothing. Green with `Turns::has_merges`, the client's
  `reset_merges`, the transcript's `R` (only with merges), the answer's speakers relabelling and
  the turns re-read, and the footer naming it. The test's first fixture was inconsistent (a merged
  turn pointing at a speaker absent from its list); corrected before the code. Committed as
  `aa33d0e7` (85 tests, the journey ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 41): the reassignment line. Red ran on `e3f4f66a` with the
  cycle's uncommitted pty test: pressing `a` marked nothing. Green with `Turn::segments` (ids),
  the client's `reassign_segments`, `a`/Enter/`n` on the transcript, `(marked)` on the selected
  speaker, and the answer's speakers plus re-read turns. Selections belong to speakers here, so
  marking is speaker-level (user confirmed). Committed as `618bb235` (86 tests, the journey
  ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 42; user-directed): the segment cursor. The user asked that segments
  be reachable with the arrows or `h`/`j`/`k`/`l` and the current one highlighted, for visual
  parity. The transcript now renders the turns' segments as spans, reverses the cursor's (the
  speakers row follows it; speaker actions act on its speaker; `a` marks its segment), and `e`/`v`
  edit (`PUT`) and revert a segment, whose answers are the task's turns. Red ran on `1079ef78` with
  the cycle's uncommitted pty test: no highlight and no prompt. The prompt row's height had not
  counted the edit prompt; fixed. The speaker and reassignment tests were updated to the cursor's
  navigation (their assertions unchanged), and the reassignment fixture now moves only the marked
  segment. The user asked for a rebase onto main mid-cycle: 122 commits replayed (the branch's
  lineage predates main's squashes), and main's MSW ADR 0066 forced this branch's UI-text ADR to
  0067. Committed as `8cadcf15` (87 tests, the journey ignored; fmt and clippy green). Lines ticked.
- 2026-11-07 (workspace, cycle 43; user-directed): the transcript's scrolling. Red ran on
  `eb3dd696` with the cycle's uncommitted pty test (the footer named no paging key). Green: the
  view follows the cursor (wrapped rows counted by the paragraph's own wrap rules, since
  `Paragraph::line_count` is unstable), PageUp/PageDown and Ctrl-U/Ctrl-D page by the visible
  height, Home/`gg` and End/`G` jump, and the footer names them - shortened so `r rename`,
  `m merge` and `d download` still fit a 200-column row (the footer test caught the overflow).
  A single `g` jumps to the start as `gg` does. The suite runs 88 tests (the journey ignored); one
  full-suite pass saw the segment-edit test time out once under load and passed alone and in the
  next full pass. Committed as `fcf17deb`.
- 2026-11-07 (workspace, cycle 44): the mouse line. Red ran on `2a6d256a` with the cycle's
  uncommitted pty test: the History tab's click fetched nothing. Green with mouse capture, a
  hit-test sharing the tab bar's pieces with the draw, click dispatch (tabs, upload area, rows,
  settings' new `select`) and a half-second double click on a history row. The test sends SGR
  mouse presses through the pty; an Escape needs a moment before the next mouse press or the
  parser swallows it. Committed as `7ed6c877` (89 tests, the journey ignored; fmt and clippy
  green). Line ticked.
- 2026-11-07 (workspace, cycle 45): the wheel line. Red ran on `0fd661a2` with the cycle's
  uncommitted pty test: the history's second row was not selected after a wheel notch. Green
  with SGR wheel events dispatched as Up/Down to the open page, so the queue's, history's and
  settings' selections and a transcript's segment cursor move (the transcript's view already
  follows). Committed as `c882f676` (90 tests, the journey ignored; fmt and clippy green).
  Line ticked.
- 2026-11-07 (workspace, cycle 46): the event line. Red ran on `c160f447` with the cycle's
  uncommitted pty test: no turns were read after `segment-edited`. Green with the client's
  `SegmentChanged`/`SpeakersReassigned` events and the transcript's re-read on either when the
  task is the open one; the snapshot state ignores them. Committed as `edf1bf01` (91 tests, the
  journey ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 47): the pasted-path line. Red ran on `43a57f76` with the
  cycle's uncommitted pty test: the paste opened nothing. Green with bracketed paste enabled,
  the tokenizer (quotes, backslash, `file://` percent-decoding, existing files only), the
  dialog opening with the first path typed and the remaining ones following after each upload.
  Committed as `83a4e817` (92 tests, the journey ignored; fmt and clippy green). Line ticked;
  the test list is empty and the item is in review.
- 2026-11-07 (workspace, the user's review of the in-review TUI): reopened, `Status: in-progress`.
  The user's decisions: the theme is chosen with a `t` key over `auto`/`light`/`dark`, and is
  **sticky across the browser and the TUI** — the service stores `themeMode` (contract change,
  migration; ADR 0068, amending ADR 0033's `localStorage`); the TUI has **no `--theme` argument**;
  `auto` reads the OS's preference first. Both frontends take their colours from **one shared token
  file** (ADR 0068). Tabs: Left/Right and `h`/`l` move, Shift+`Q`/`H`/`S` jump, the first letter
  underlined, `q` still quits. The stenographer placeholder goes in the tab bar and on the empty
  states, with a dummy animation that moves only while a task runs. Settings: dropdowns for every
  setting with a set of values (the default frontend chosen from the installed ones, never typed),
  in-row editing for the numbers, the terminal command starting from the current default, and
  descriptions under the list with the labels kept. Second amendment drafted.
- 2026-11-07 (the user): the second amendment (16 lines) and its order approved — the visible
  defects first (queue rows at 80/60 columns, the redraw after the dialog, the dialog's folders and
  guidance), then tabs, settings, the shared colours and mode, the e2e and the mascot.
- 2026-11-07 (the user): **audio playback is in scope** — the user never ruled it out, and a
  terminal can play when an audio device is there; the Goal, the decisions and the acceptance
  criterion that said otherwise are corrected. Decisions: in-process playback with `rodio` (cpal;
  `alsa-lib` added to the dev shell, `7ad46699`, and the Linux CI and packaging paths need it too);
  a player bar in the transcript and `Space` on a History row; keys `Space` play/pause, `p` the
  cursor's segment alone, `[`/`]` ∓5 s, `Enter` seeks to the cursor's segment, the cursor following
  the playhead; without a device a notice says so and the TUI stays usable; tests play on a silent
  real-time output. Six playback lines appended to the second amendment, after its defects.
- 2026-11-07 (workspace, cycle 48): the narrow-queue line. Red ran on `6328bc0c` with the cycle's
  uncommitted pty test: at 80 columns the long name was cut without `…`
  (`field-recording-interview-wi`). Green: the queue's columns are a list from which phase, then
  language, then size drop out while the name would get fewer than 16 columns, and the name is
  shortened with `…`. The full run hit `reset_merges`'s old race (it waited on the tab bar's
  "History" before the list arrived); fixed in its own commit, `f44d434f`. Committed as
  `8873ca0a` (93 tests, the journey ignored; fmt and clippy green). The same screen showed the
  queue's footer cut off, as the user reported for History: added as a discovered line.
- 2026-11-07 (the user): the TUI must be a fully functional frontend with good UI/UX. A browser
  inventory (153 behaviours: shell 14, queue 38, history 15, transcript 42, settings 23, global 21)
  against the TUI gave the third amendment (24 lines), approved as listed — FAILED and CANCELED
  tasks move from the queue to History, as in the browser. Order approved: screen designs for
  sign-off first, then the shared colours and theme, the shell, History, the queue, the transcript
  (playback first), the settings, the mascot — after the second amendment's open defect lines.
- 2026-11-07 (the user, design review): the drawn screens (`docs/design/tui/`) "look pretty good",
  with changes, all redrawn (`776c3f22`): instructions are never clipped — footers wrap (the footer
  line confirmed and reworded); the queue's progress is a thin line drawn like the transcript's
  seek line, which the user liked; the settings dropdown floats over the fields without moving
  them, and Escape closes only the dropdown; the stenographer placeholder was too small — it is now
  a 3-row tile at the left of a 3-row header; and a letter key is shown by highlighting that letter
  inside its action word instead of "d download". Signed off by the user the same day: "no
  blockers, approved" — the cycles build to these designs.
- 2026-11-07 (workspace, cycle 49): the redraw line. Red ran on `7885b7fc` with the cycle's
  uncommitted pty test: closing the dialog wrote no full clear. Green: when any overlay closes
  the clear is queued with the next frame and the buffers reset, so every cell is written again
  (`Terminal::clear` was tried first and stalled on its cursor-position query, which the pty does
  not answer and a full-screen TUI does not need). The full runs then showed timing faults in the
  pty tests under load, all in the harness, fixed in `ec231aa0`: one connection at a time in the
  stand-in, a settings capture that could take the TUI's startup read, Escapes followed by sleeps
  instead of waits (the next byte parsed with them), and waits on text the queue page also shows.
  Three full runs: 94 passed each; fmt and clippy green. Committed as `fff5294b`. Line ticked.
- 2026-11-07 (workspace, refactor after cycle 49, approved): the highlight reader shared as
  `Tui::wait_for_highlight`, and every GET wait in a test that runs a TUI reports its screen
  when the read never comes (`b8b73e31`; 94 passed in two full runs).
- 2026-11-07 (workspace, cycle 50): the go-up half of the dialog line, split from its guidance half
  so each is ticked by its own test. Red ran on `b8b73e31` with the cycle's uncommitted pty test:
  the list had no `../`. Green: `../` heads the list wherever the folder has a parent, and Enter on
  it, Backspace with nothing typed, and Left on the file list browse the folder above; the root
  shows no `../` and going up keeps it. The list's rows are one method the drawing and Enter
  share. The two browser tests now step past `../`. Committed as `0643accf` (95 tests, the journey
  ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 51): the typed-path line. Red ran on `8dee7612` with the cycle's
  uncommitted pty test: `~/music` was taken literally and refused as "not an audio or video file"
  (and the relative `sub` only resolved because the dialog had opened in the process's own folder).
  Green: a typed path is resolved against the shown folder, `~` against `HOME`; a folder opens, a
  file is chosen, and a missing path is reported inside the dialog, which stays open. Two earlier
  tests had pinned the old route (an "upload failed" notice after an attempted request): they now
  expect the in-dialog report and close the dialog before their next step, and `upload.rs`'s
  format check uses a real non-audio file instead of one that never existed. Committed as
  `3efaa4c5` (96 tests in two full runs, the journey ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 52): the dialog's guidance. Red ran on `5f97dff2` with the cycle's
  uncommitted pty test: the catalog had no hints. Green: the title "Upload audio", a folder line,
  two hint lines (Enter opens or uploads; ← or Backspace goes up, or type a path) and a footer
  naming the dialog's keys, all from the catalog (only the TUI reads these keys); the list takes
  the height the other lines leave. The design's "Space marks" hint waits for the multi-file
  line, which adds marking: a hint never names a key that does nothing. The tests that recognised
  the open dialog by the old "Upload:" title now read the new one, and `upload_up`'s root check
  reads the folder line. Committed as `9bf24844` (97 tests in two full runs, the journey ignored;
  fmt and clippy green). Line ticked.
- 2026-11-07 (workspace, cycle 53): the tab keys. Red ran on `a65134d8` with the cycle's
  uncommitted pty test: no tab letter was underlined. Green: `h`/`l` and Left/Right move between
  the tabs (wrapping, like `Tab`), Shift+`Q`/`H`/`S` open them, and each title's first letter is
  underlined (plain under `NO_COLOR`, whose approved rule is no SGR at all - the cycle's first
  full run caught the underline there); the footers name the new keys. A transcript keeps the
  arrows and `h`/`l` for its segments. On Settings, Left/Right still change a value until its
  dropdowns land, so that half moved to the dropdown line; `h`/`l` already move there. `h` no
  longer opens History, so the tests that did so press `H`, and the unused `OpenHistory` action
  is gone. Committed as `5de7ec57` (98 tests in two full runs, the journey ignored; fmt and clippy
  green). A run before it failed once in `delete_task.rs`: its wait accepted a frame caught
  mid-repaint, which also lacks the deleted row; that wait now requires the remaining rows too
  (`5393eccb`, its own commit). Line ticked.
- 2026-11-07 (the user): shortcut keys in other keyboard layouts (a Hebrew `י` counting as `h`) are
  **not** taken on. A terminal sends characters, not keys; the kitty keyboard protocol can report
  the base-layout key, but only some terminals send it and crossterm 0.29 drops that field; the
  alternative, a translation table per layout, cannot cover the many layouts and variants
  correctly. The user: "If there's no generic solution for this, then we'll have to skip it." Not
  a test-list line.
- 2026-11-07 (the user, cycle 53's refactor checkpoint): both suggestions approved. The pty waits
  that accepted a mere absence now also wait for what must be drawn (`027cb17a`); the tab bar
  keeps each tab's parts instead of searching its label for the letter to underline (`d484f2ad`).
  98 tests green in two full runs after them.
- 2026-11-07 (workspace, cycle 54): the settings dropdowns. Red ran on `d484f2ad` with the cycle's
  uncommitted pty test: Enter on the language opened nothing. Green: Enter on the device, language,
  speakers or default frontend opens a bordered dropdown under the row's value, titled with the
  setting, listing every option (devices with their GPU names, languages as code and name) with
  the stored one ticked and under the cursor, and `Enter choose · Esc close` on its last line. It
  floats over the rows below (the labels keep their lines), opens above the row when there is
  more room there, and shows eight options at a time, scrolling with its cursor. Up/Down or `k`/`j`
  move, Enter chooses - as a step from the stored option, so the speaker-count rule and the
  whole-settings save are the ones Left/Right use - and Escape closes it alone; a mouse click on
  another row closes it too. The Settings footer says `enter choose / edit` (the design's words),
  and `footer.rs` follows. Left/Right keep changing a value on Settings: the number rows have no
  other way to change until they are edited in place, so switching tabs with them moved to that
  line. The theme row comes with the theme line. Committed as `b1d652e9` (99 tests in two full
  runs, the journey ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (the user, cycle 54's refactor checkpoint): both suggestions approved - a device is
  named in one place for its row and its dropdown (`5baf124c`), and every list of choices, the
  frontends included, steps with one generic helper (`fcc098f0`); 99 tests green in two full runs.
  The user on the open decision: Left/Right make no sense on a row with a dropdown. So cycle 55
  ends every value change by Left/Right on Settings, dropdown rows included.
- 2026-11-07 (workspace, cycle 55): numbers edited in their row. Red ran on `fcc098f0` with the
  cycle's uncommitted pty test: Enter on the threads opened nothing. Green: Enter on the threads,
  speaker count or max speakers puts the row's number in place with a bar for the cursor and
  `Enter save · Esc cancel · ↑/↓ ±1` beside it (an automatic thread count or an unchosen speaker
  count starts empty). Digits and Backspace edit it (five digits at most), Up/Down change it by
  one (a thread count below 1 empties it - automatic; the speaker counts stay within 1..20), Escape
  leaves the stored value, and Enter checks the range: outside it the notice gives the browser's
  own words (`settings.threads-invalid`, `settings.speaker-count-invalid`,
  `settings.max-speakers-invalid`), nothing is sent and the number stays open; inside it the
  settings are saved whole, an emptied thread count as none. A speaker count outside `fixed` is
  held by its row without a write, as before. Nothing on Settings changes with Left/Right any
  more: they move between the tabs as everywhere, and the footer says so. The tests that changed
  settings with Left/Right (`settings.rs`, `mouse.rs`, `wheel.rs`) now use the dropdowns and the
  in-row numbers, and `step_threads`, which only Left/Right used, is gone. A mouse click on
  another row closes an open number as it closes a dropdown. Committed as `230c9943` (100 tests in
  two full runs, the journey ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (the user, cycle 55's checkpoint): both refactors approved - `SettingsScreen::handle`
  dispatches to one handler per mode (`0d1dcc44`), and the settings tests share their fixtures
  (`06ac707f`); 100 tests green in two full runs. The user approved the terminal command's plan as
  proposed.
- 2026-11-07 (workspace, cycle 56): the terminal command edited in its row. Red ran on `06ac707f`
  with the cycle's uncommitted pty test: with none stored the row showed `-`. Green: the row shows
  the stored command, or `default: <what opens today>` - on Linux the words before the TUI's
  command (`$TERMINAL -e`, else the first of the terminal chain on `PATH`), on Windows and macOS
  `PowerShell`/`Terminal`, on Linux with no terminal found `no terminal found`. Enter edits it in
  the row with a bar for the cursor and `Enter save · Esc cancel · empty: <default>` beside it,
  starting with the stored command or, on Linux, the default's prefix; the bottom-row prompt is
  gone (with its catalog key). Enter on an unchanged command sends nothing - so a Linux edit that
  keeps the default leaves the chain in charge - and a changed one is saved whole, an emptied one
  as none. Escape leaves the stored command. `settings.rs`'s two assertions of the old prompt and
  the `-` row now read the in-row edit and the `default:` text. The client library now answers the
  default with `default_terminal` in `stt-client`, which shares `LINUX_TERMINALS` with
  `terminal_command`. The first full run caught the older assertion the row's `default:` text
  breaks (it ran on that one change, `settings.rs:403`). Committed as `2b3061b4` (101 tests in two
  full runs, the journey ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (the user, cycle 56's checkpoint): all three refactors approved. The client now owns
  `host_os` and `on_path`, which `stt-app` and the settings screen both call (`b3f07e9b`,
  `efc6fdd5`); the settings tests share their row-value helper through the fixture module
  (`ba3acc74`); and the settings screen answers its own key mode (`c171eaab`). 101 tests green in
  two full runs after them.
- 2026-11-07 (workspace, cycle 57): the selected setting's description. Red ran on `c171eaab` with
  the cycle's uncommitted pty test: no description was drawn. Green: under the list, two lines at
  the bottom - so a narrow terminal wraps rather than clips - the selected row's `Label —
  description`, from eight new catalog keys (`settings.<field>-description`), the device's the
  design's own sentence. At 60 columns the test checks every row's whole sentence appears and the
  previous row's is gone. The list keeps the rest of the page, and a dropdown is confined to it so
  it never covers the description. Committed as `72eeaae5` (102 tests in two full runs, the journey
  ignored; fmt and clippy green). Line ticked.
- 2026-11-07 (the user, cycle 57's checkpoint): the refactor approved: `draw_settings` now builds
  rows through `value_cell`, the description through `description_line`, and the dropdown's anchor
  through `list_line` (`9c49171f`); 102 tests green.
- 2026-11-07 (workspace, cycle 58): the browser's colours come from the shared token file. Red ran
  on `68781fac` + the cycle's changes with the uncommitted Vitest test: `ui-theme/colors.json` did
  not exist. Green: the file holds every colour role (each a `{light, dark}` pair), the five
  status groups' background and text, the speaker palette (moved out of `speakerPalette.ts`) and
  the illustration's colours; `theme.ts` maps it into `ThemeTokens` through `colorsFor` and
  `illustrationFor`, explicitly, so a role the file drops fails the type check. Elevation shadows
  stay in `theme.ts` (they are not a colour role). The tokens test resolves the file per theme and
  compares it with both themes, so a role on one side only fails there too. The test caught a bug
  in itself first (it read the speaker palette as a group rather than a pair; fixed in the cycle).
  The contrast and speaker-tint tests run over the shared values unchanged. Verification: the
  frontend's 312 tests in 44 files, `tsc --noEmit`, and prettier over the file and the three theme
  modules, all green - run here through `node node_modules/vitest/vitest.mjs`, since `pnpm test`
  cannot start in this sandbox; the user's `pnpm --dir frontend test` is still theirs to run.
  Committed as `d35356e0`. Line ticked.
- 2026-11-07 (the user, cycle 58's checkpoint): no refactoring suggested; go ahead with the theme
  mode.
- 2026-11-07 (workspace, cycle 59): the stored theme mode. Red ran on `67719c8a` with the cycle's
  tests: `themeMode` on the settings and `ThemeMode` did not exist (compile). Green:
  `V18__theme_mode.sql` (`V19__theme_mode.sql` since the merge of item 88, whose
  `V18__app_language.sql` kept the number) adds `settings.theme_mode` (`TEXT NOT NULL DEFAULT
  'auto'`, CHECK auto/light/dark), so a database from before the column reads `auto` -
  `DatabaseSpec` opens one
  migrated only to V9 (V10 and V12 are class migrations a raw Flyway cannot resolve) and finds
  `ThemeMode.AUTO`. `ThemeMode` joins `Settings`, the table, the repository and the routes: `GET`
  answers `themeMode`, `PUT` takes it (absent = `auto`, like the other whole-write fields) and
  answers 400 `invalid-theme` for anything else, error shape and all, and `settings-updated`
  carries it. The `docs/api` settings examples gained the field (the specs pin their shape),
  `docs/api/README.md` and `docs/architecture.md` name the migration, the browser's `Settings` type
  and its form send the stored mode on every write (changing another setting keeps it), and the
  Rust `Settings` reads a missing one as `auto` and always writes it. `DatabaseSpec`'s pinned
  migration count and checksum list gained V18 (V19 since that merge). `gradle check` also
  caught that this branch's own
  `tuiJourney` block in `build.gradle.kts` failed `ktlintCheck` (a `///` comment and an over-long
  `logger.warn`), fixed in its own commit (`d3507d5b`) - the first green backend gate on this
  branch. Verification: `gradle check` green (399 tests), the client's 102 tests with fmt and
  clippy, the frontend's 312 tests in 44 files, and the docs' link, architecture-name and
  diagram checks. One full frontend run failed the speaker-tint test once and five reruns since
  passed; recorded in `docs/known-issues.md` as a suspected flake. Committed as `51b81fd1`. Line
  ticked.
- 2026-11-07 (workspace, cycle 60): the TUI draws from the shared tokens. Red ran on `6470b47f`
  with the cycle's uncommitted pty test: a panel's border was `Default`, not the border role.
  Green: `stt-tui/src/theme.rs` builds the palette from `ui-theme/colors.json` (the same file the
  browser reads) in the stored `themeMode` - `auto` asks the OS (freedesktop `gsettings`
  `color-scheme`, macOS `AppleInterfaceStyle`, Windows `AppsUseLightTheme`) and falls back to dark.
  The page is painted in the background role, panel borders in border with titles in accent, the
  tab bar accent for the open page and muted for the rest, statuses in their group's colour
  (RUNNING accent, COMPLETED done, FAILED danger, CANCELED gone, the rest pending), speaker names in
  their tints, and the selected row and dropdown cursor on the accent tint instead of reversed
  video; the header turns muted rather than bold. The startup settings read now also hands the mode
  over (`Message::Theme`), and `settings-updated` follows it (the `t` key and the live switch are
  the next line). The pty harness gained `Scene` (cells with their colours), `wait_for_cell`, and a
  highlight rule that reads both styles (reversed without colours, off the page's background with
  them) - the dropdown test's own helper uses it too. `NO_COLOR` still writes no SGR (its existing
  test). Verification: 104 client tests in two full runs of the suite, fmt and clippy green, and
  the colour tests three repeats in a row after the test was made to wait for the mode's colour
  rather than its text (the first run caught it asserting the pre-settings dark palette in the
  light-mode test). Committed as `ca42d749` (104 tests in two full runs, the journey ignored;
  fmt and clippy green). Line ticked.
- 2026-11-08 (the user, cycle 60's checkpoint): the refactor approved - the palette answers a
  status's colour (`Palette::status`), out of `view.rs` (`a5519ab9`).
- 2026-11-08 (workspace, cycle 61): the browser's theme control and the stored mode. Red ran on
  `0e9f67b6` with the cycle's Vitest test, which replaces the old local-only cycling test (its
  contract - the choice lives in localStorage - is what this line changes): the click wrote
  nothing. Green: a new `app/useThemeMode` draws in the stored `themeMode` once the settings are
  read, and in the last-known mode (localStorage, `stt-theme-mode`) before; the control cycles
  auto → light → dark by sending the whole stored settings with the next mode through the settings
  machine's `SAVE`, and shows the stored answer; a `settings-updated` from another frontend
  repaints the page and becomes the last-known mode. The control is disabled until the settings
  are read - a write needs them whole. `App` now puts the theme provider inside the settings
  provider (`ThemedApp`), and `shared/theme/themeMode.ts` keeps only the pure helpers, its
  `ThemeMode` now the API's. The e2e suite follows: one `setTheme` helper waits for each step's
  answer (the label changes after the service answers, no longer at once) and replaces the four
  specs' own loops, and an automatic `themeReset` fixture puts the stored mode back to auto before
  each spec, since a switched mode now outlives the browser context that chose it. Verification:
  the frontend's 312 tests in 44 files, `tsc --noEmit`, and prettier over the changed frontend and
  e2e files; the e2e package's `tsc` and Playwright run cannot start in this worktree (no
  `e2e/node_modules`) and are the user's. Committed as `03764965`. Line ticked.
- 2026-11-08 (the user's `pnpm --dir e2e test`, in a colour terminal): the run stopped before
  Playwright at `e2e/lib/type-check.test.ts` - an existing check on `main`, not this branch's:
  `tsc` 7 printed its coloured `file:line:col` form, which the test's `file(line,col): error`
  assertion does not match. Reproduced here: `FORCE_COLOR=1` without `CI` or `NO_COLOR` fails it
  every time (Node's test runner hands colour on to its children in a colour terminal); CI sets
  `CI=true` and never saw it. At the user's request (one-line fix on this branch), the test runs
  `tsc --pretty false` (`f4661ede`); it now passes with `FORCE_COLOR=1`, with `CI=true` and under a
  real colour terminal, and the e2e package's `tsc -p tsconfig.json` passes over this branch's
  e2e changes. The Playwright journey itself is still the user's to run.
- 2026-11-08 (the user's e2e journey, `pnpm --dir e2e test` in the worktree after
  `(cd backend && gradle installDist)`, at `b65b9236`): 57 passed, 1 failed, 1 skipped
  (`18-macos-metal`, a macOS-runner spec), 1 not run (the run stops at its first failure). Every
  spec this item's theme work touches passed - `15-screenshots`, `16-header-and-navigation`,
  `52-edit-segment` (its dark-theme editor test included) and `59-header-backdrop` through the
  new `setTheme`, every later spec from the `themeReset` default, and `14-contract`, `17-settings`
  and `66-settings` over the settings carrying `themeMode`. The failure,
  `87-writes-wait-for-the-connection`, is not this branch's: the trace shows every reconnect after
  its restart answered 406 - in app mode (no `E2E_BACKEND_CMD`) browser sessions die with the
  service process, and the spec never signs in again; CI runs `./gradlew e2e`, whose dev-mode
  backend has no sessions. An earlier attempt had run against a backend installed before V18 (the
  theme migration, V19 since the merge of item 88; the harness builds one only when none
  exists). Both are recorded in `docs/known-issues.md`, with
  the 500 a body with an unknown key gets and the UI build that does not reinstall a moved
  lockfile; the type-check row there leaves the backlog, fixed by `f4661ede`.
- 2026-11-08 (`48125732`, a branch fix at the user's request): the e2e journey builds this
  checkout's backend once per run - the global setup runs `installDist` before it starts the
  shared service, so a run after a backend change no longer tests an older install;
  `./gradlew e2e`, whose task already depends on `installDist`, sets `E2E_BACKEND_INSTALLED` and
  skips the second build. Run here both ways: 22 s with nothing to rebuild (a cold Gradle start),
  and an immediate return under the flag. The known-issues row is gone. Refactor `dd271d80`
  (approved): `settingsUpdateOf` in `frontend/src/shared/api/client.ts` turns stored settings into
  a write, shared by the header's theme control and the Settings page's commit (312 frontend
  tests, `tsc` clean).
- 2026-11-08 (cycle 62, `555497e1`, red at `dd271d80`): the TUI's `t` key, the live switch, and
  `auto`'s order. Red: `t` wrote nothing ("no command arrived within 20s") and a terminal reporting
  a white background still drew dark; the dark fallback passed already and stays pinned. A
  correction to cycle 60's entry: it read as if `settings-updated` already switched the colours,
  but only the startup read did - this cycle makes the event repaint. `Message::Theme` became
  `Message::Stored(Settings)`: the TUI keeps the stored settings from the startup read,
  `settings-updated` and every save, and `t` writes them whole with the next mode. Going green
  found a real defect rather than a test race: a second `t` before the service answered the first
  cycled from the old mode and wrote it again, so the next mode is now drawn at once and a refused
  write puts the previous settings back with the save-failed notice. `auto` reads its clues once
  at startup (`theme::Clues`): the OS first, and only when it says nothing the terminal - via
  `terminal-colorsaurus` (OSC 10 and 11 with a DA1 query behind them, so a terminal that cannot
  answer is known at once), asked before the keyboard thread reads the terminal; `NO_COLOR` asks
  nothing. A missing macOS `defaults` no longer reads as the light appearance. The resolution order
  and the three OS answers are unit tests (`theme.rs`), written with the implementation since the
  OS cannot be steered from a pty test; the pty test empties `PATH` so the OS says nothing. The
  harness's terminal now answers DA1 always and OSC 10/11 when a test gives colours. `t theme`
  joined the four footers in `ui-text/en.json`. Verification: 113 client tests in two full runs,
  fmt and clippy green. A real terminal's answer (and each OS's own preference) is the user's to
  try: `stt-tui` with the stored mode `auto` in a light and a dark terminal profile.
- 2026-11-08 (the user's check of cycle 62, niri on Wayland, NixOS): the TUI started dark under
  the dark desktop, `t` cycles the mode, and a change in one TUI repaints another against the same
  service. Closing a dialog redraws what was behind it. The user asked for a visible toggle: the
  tab bar's theme-mode line now also cycles the mode on a click. Reported that hints still read
  `p pause`: that is the pending in-place highlight line, which the user chose as the next cycle.
  A discovered line, confirmed: the language dropdown filters as the user types, and no dropdown
  moves with `j`/`k` (the user: every dropdown, arrows only). The remaining styling is the
  third amendment's design lines.
- 2026-11-08 (cycle 63, `0f309f4d`, red at `4061512f`): keys highlighted in place. Red: the footer
  still wrote `p pause` (and under `NO_COLOR` lacked `[p]ause`). The catalog now brackets every
  key a hint names - `[p]ause`, `re[v]ert`, `[Enter] open` - and `client/stt-tui/src/hints.rs`
  draws it underlined in the accent colour without the brackets; `NO_COLOR` keeps the brackets,
  since it writes no attribute. The markup is read from the template before placeholders are
  filled, so a file name with brackets never becomes a key (a unit test pins the split). Every hint
  went through it: the four footers, the help reference, the upload dialog's hints and footer, the
  upload area, the empty queue, the delete prompt and the three settings prompts. ADR 0067 states
  the convention (it is this branch's ADR). The user asked mid-cycle that vim keys always come
  second: `Home/gg`, `End/G`, `Shift-↑↓ or J/K`; the other hints already did. Seven existing
  tests pinned the old forms (`u upload`, `r rename`, `enter open`, `enter confirms`,
  `esc cancel`) and now read the new ones; `upload_guidance.rs` compares the catalog's text as a
  colour terminal shows it. Verification: 115 client tests in two full runs, with
  `upload_guidance` failing in both on its old raw-catalog comparison; after its fix it passed
  twice alone, with fmt and clippy green. Discovered and confirmed during the cycle (the user,
  2026-11-08): the focused upload area takes the row tint as well as its `>`; every text prompt
  shows the terminal cursor (the upload dialog's path named too); the download dialog's Up/Down
  move between its fields. The clipped transcript footer the user saw ("enter a") is the pending
  footer-wrap line; playback is the pending player lines.
- 2026-11-08 (cycle 64, red at `5472083f`): the footer wrap. Red: at 80 columns the queue footer
  lost `Space queue pause`, `upload`, `theme`, `quit` and `? help`. `hints::rows` packs a footer's
  hints into as many rows as the width needs, a hint never split (its own unit test checks 20, 40,
  80 and 200 columns over three footers), and `view::draw` gives the footer that many rows, so the
  panel above simply takes one or two fewer. Following the user's instruction that tests must not
  use real timers, the TUI now draws each frame as one synchronized terminal update (its begin and
  end sequences; a terminal shows all of it or none, so repaints cannot tear), and the pty harness
  wakes its waits on a condition variable the output reader bumps at each frame's end marker -
  never on a 50 ms poll; the timeout only bounds a TUI that never draws what is expected. That made
  waits exact, which exposed three tests whose predicate was weaker than their assertion and had
  been riding the old overshoot: `commands.rs` waited for the statuses but asserted the queue
  order, and now waits for both; `scrolling.rs` measured a half-drawn frame, so it now counts the
  visible rows after the transcript's first frame (first segment marked, footer drawn) and derives
  the page size from them instead of the old hard-coded 16 (the footer takes three rows now, so
  the viewport is 15), with Ctrl-U checked as Ctrl-D's inverse.
  Chasing a flaky `footer_wrap` (4 of 9 runs) found a real defect that is not this line's: a key
  pressed in the same moment as a resize is lost, by crossterm 0.29's mio reader; it is filed in
  `docs/known-issues.md` with the `use-dev-tty` candidate, and the test waits for the resized frame
  before pressing so it stays out of the race (a dedicated test would be flaky). The 3-5 s each
  transcript key takes to show predates this cycle: the same test runs 23.6 s with this cycle's
  changes stashed. Verification: `scrolling` five times green, `footer_wrap` five times green, the
  full client suite twice, fmt and clippy green. Refactors, each on its own commit: `d30078e7` (the
  three colour tests share one token helper), `2e74d4b9` (`Message::Fallback` gone; a dismissal
  answers with `Stored`) and `5472083f` (the settings screen and the theme key save through one
  function) - the last is also the commit the red test ran on.
- 2026-11-08 (cycle 65, `dad46d2f`, red at `83ab676b`): the visible cursor. Red: neither prompt
  showed one (ratatui hides the cursor unless a frame places it). The TUI sets the shape to a
  blinking block at startup and restores the terminal's own on exit (the user, mid-cycle: "a
  blinking full rectangle like in omp" - DECSCUSR 1, which the screen parser reports). A frame
  places the cursor where a draft ends: the segment edit and rename prompts, the upload dialog's
  path (the modal's fourth inner row), the download dialog's path while it holds the focus, and a
  settings number or the terminal command in its row's value cell - its `▏` bar, which the block
  covers, stays as the tests' readable mark of where typing goes. Closing the prompt places none,
  so the cursor hides again. The test covers the segment editor, both dialogs' paths and a
  settings number, and asserts the shape too (a text-only check would pass on an underline
  cursor). One trap found on the way: Escape immediately followed by `d` reaches the TUI as
  Alt+d, so the test waits for the prompt to close before the next key. Refactors `95b6f7c7` (one
  table maps a page to its help and footer keys) and `83ab676b` (the harness scans marked cells in
  one place). Verification: the cursor test, the settings suites, and the full client suite twice
  (119 tests), fmt and clippy green. On a real terminal the shape is the user's to see.
- 2026-11-08 (cycle 66, red at `ee3428e0`): the focused upload area's tint. Red: focused, its
  inside was the page's background (the `>` alone marked it). Its inner area is now painted in the
  accent tint while it holds the focus, the block's border left alone, so the block cursor that
  lands there no longer sits on a bare background. `NO_COLOR` keeps the `>` alone, as for a
  selected row. The test reads the tint's colour (the scene's cell backgrounds), not just the
  text, and checks it goes with the focus. Verification: the upload-area and layout tests, and the
  full client suite twice (119 tests), fmt and clippy green.
- 2026-11-08 (cycle 67, red at `5d81a696`): the download dialog's arrows. Red: Down left the focus
  on the path (the dialog ignored Up and Down). They now set the focus to the path and the format
  as Tab toggles it, and Left and Right keep stepping the format only while it has the focus. The
  dialog gets its own footer while it is open - `download.footer`, so the keys it now offers are
  stated: `↑/↓ path / format · ←/→ format · Enter download · Esc cancel` (the wrap from cycle 64
  keeps it whole on a narrow terminal). The test opens the dialog from a transcript, moves down,
  steps the format to `srt`, moves back up and reads the footer. Verification: the download tests,
  and the full client suite twice, fmt and clippy green.
- 2026-11-08 (cycle 68, red at `42d3e7e5`): Right in the upload dialog. Red: the footer did not
  name it and Right did nothing (red caught by the footer assertion first; the navigation check
  below it is the behaviour's). With the browser focused, Right now enters the selected folder, as
  Enter does, and does nothing on a file - Enter stays the one that uploads it; Left keeps going up
  and the option fields keep Left/Right for their values. The dialog's footer names it:
  `← or Backspace up · → open folder`. The test makes a folder with a file inside, opens it with
  Right, goes back up with Left, and presses Right on a file to see it stay. Verification: the
  upload-guidance and upload-area tests, and the full client suite twice, fmt and clippy green.
- 2026-11-08 (cycle 69, red at `09e91d18`): the combined Speakers row. Red: choosing `fixed` never
  opened a count editor (the old screen had a separate, dead-end-ish "Speaker count" row). The row
  is now one: its dropdown holds auto/off/fixed; choosing `fixed`, or choosing it again on a stored
  fixed row, opens the count in the row's value (prefilled with the stored count, empty when there
  is none); Enter validates 1..20 and saves `fixed` with the count, Up/Down step it, Escape leaves
  the mode alone; auto and off save with a null count. The value reads `fixed · 3`
  (`settings.speakers-fixed`); `Field::SpeakerCount`, the staged count and the count-first notice
  are gone, and the catalog drops its three keys. Tests updated: the old first-count test is
  deleted (the new one covers the flow), the description, dropdown-label, number and
  default-frontend tests lose the removed row and the row indices they counted. Verification: the
  settings suites, and the full client suite twice, fmt and clippy green.
- 2026-11-08 (cycle 70, red at `d3af7a89`): `q` like Escape. Red: the first `q` in a transcript
  quit the TUI at once (the test's exit check saw it gone). `q` now maps to the same action as
  Escape - close the overlay, leave the page for the queue, quit only there; Ctrl-C alone still
  quits at once, and its action's doc says so. Where Escape cancels an overlay and `q` is no text -
  a dropdown, a number, a confirmation - `q` cancels it too; in a text prompt (the upload and
  download paths, the terminal command, a rename or an edit) it stays a character and Escape is
  the cancel. The footers keep `q quit`: from the queue it is one press, and the `?` reference and
  the wrapping footer stay as they were. Verification: the quit, key-hint, help and footer tests,
  and the full client suite twice, fmt and clippy green.
- 2026-11-08 (cycle 71, red at `c7d3d371`): the Linux terminal chain. Red: kitty was not in it, so
  `terminal_command` answered None and the Settings row read "no terminal found" (the user's
  question: could there be one generic setup? - there is no OS API for a default terminal on
  Linux, so the generic layers come first and the chain names what is left). After `$TERMINAL`,
  `xdg-terminal-exec` and `x-terminal-emulator`, the chain now tries kitty and foot (the program
  directly), alacritty and ghostty (`-e`) and wezterm (`start --`), before the desktop terminals;
  `default_terminal` names the same one the settings row shows. The test covers each launcher's
  words, the generic layers still winning, and the desktop terminals still following. `$TERM`
  stays unused: it names a terminfo entry, not a program. Verification: the terminal tests, and
  the full client suite twice, fmt and clippy green.
  Follow-up (the user has `xdg-terminal` from xdg-utils, but not `xdg-terminal-exec`): the chain
  does not adopt `xdg-terminal`. Its generic path, the one a niri session takes, runs
  `command -v $TERM` - it treats `$TERM` as a program name, which `xterm-kitty` is not (that is
  kitty's terminfo entry), so it fails on any correctly configured kitty; and it hands the command
  to the terminal as one quoted argument, which a terminal starts as a single program name, so
  the TUI's arguments would be lost. `xdg-terminal-exec` (the freedesktop launcher the chain
  already prefers) does neither, and the named emulators cover the rest. `$TERM` therefore stays
  `xterm-kitty`: it names terminfo data, not a launcher, and changing it to `kitty` would break
  every program's terminfo lookup to please one broken script.
- 2026-11-08 (cycle 72, red at `20fac0b9`): the merge and reassign guidance. Red: pressing `m` or
  `a` showed only a `(merging)` / `(marked)` suffix in the Speakers panel, with nothing saying what
  to do next (the test waited for "Speaker 1 marked" and timed out). A transcript now draws a line
  under the Speakers panel while a mark stands: a marked speaker is named with the next step and
  how to unmark, and marked segments are counted the same way; the line takes as many rows as its
  wrap needs (the footer's rule), and the transcript keeps the rest. Catalog:
  `transcript.merge-hint` and `transcript.reassign-hint`; `Transcript` gained
  `marked_segment_count`. The test marks a speaker, unmarks it, marks a segment, unmarks it.
  Verification: the guidance test, and the full client suite twice, fmt and clippy green.
- 2026-11-08 (the user's correction after cycle 72): the guidance's words are the browser's, not the
  code's. "Marked", "unmarks" and "assign" were internal state names; the line now reads "Merging
  Speaker 1 · go to another speaker and press m to merge it into that one · m cancels" and "2
  segment(s) selected · press Enter on a speaker to move them there · a cancels", with the source
  speaker suffixed "(selected)" instead of "(marked)" - the browser's "Merge speakers…", "N
  segments selected", "Move to…", "Cancel". The strings were shown to the user and approved before
  committing. Verification: the guidance, reassign and speakers tests, and the full client suite
  twice, fmt and clippy green.
- 2026-11-08 (cycle 73, red at `d420b2bc`): the Speakers panel takes the keyboard. Red: Up from
  the first segment kept the cursor on it (the panel could not be reached; Enter could only assign
  through the cursor's speaker). `Transcript` gained `speaker_focus` and a `speaker_cursor`:
  SegmentPrevious at the first segment focuses the panel at its bottom row, Up/Down move there,
  SegmentNext past the last row returns to the first segment, and page, start/end, edit and revert
  leave the panel. `speaker_index()` returns the focused row, so `m`, `r` and Enter act on it;
  Enter on the panel renames the focused speaker unless segments are selected, when it moves them
  (the user's choice, asked before coding). The view highlights a speaker row only while the panel
  has the keyboard - the user's correction: navigating segments never highlights a name on top. The
  test reads the panel row's tint off the screen (comparing with the page's background) because the
  segment rows repeat speaker names. Verification: the focus test, and the full client suite twice,
  fmt and clippy green.
- 2026-11-08 (cycle 74, red at `2117ba93`): the turn's speaker tint. Red: the test's comparison
  with the panel's tint found the name in the default colour. Each turn's
  name span now takes the tint the Speakers panel gives that speaker, found by the speaker's
  position in the turn's speaker list; the clock and the text keep the default colour, and
  `NO_COLOR` writes no colour at all. The test compares the name's foreground with the panel's own
  cells rather than repeating the tokens, and checks the two speakers differ. Verification: the
  turn-tint, colour, speaker, guidance and scrolling tests, and the full client suite twice, fmt
  and clippy green.
- 2026-11-08 (cycle 75, red at `12dcbd3f`): the turn block layout. Red: after the 40-column resize
  the first turn ran as one clipped line and the turns sat on consecutive rows. The transcript now
  wraps each turn itself (`wrap_runs`, continuation lines indented by `INDENT`, the line's runs of
  spaces kept as drawn), leaves one blank row between turns, and pages by the segments of the turns
  that start in the view (`Transcript::set_page` replaces the row viewport). A first version
  collapsed the two spaces around the clock, which `reassign` and `events_refresh` pin, and counted
  only whole turns as a page; a full-suite run caught both before the cycle was reported. The
  `scrolling` test measured its page on screen but assumed one more Ctrl-D reaches Line 30, true
  only while 15 one-line turns fit 24 rows; with blank rows 8 do, so it now expects Ctrl-D one
  measured page further and Ctrl-U one page back - every step still asserted, End/`G` still reach
  Line 30. Verification: the turn-spacing, reassign, events-refresh and scrolling tests, and the
  full client suite twice, fmt and clippy green.
- 2026-11-08 (refactors after cycle 75, approved by the user): a turn block holds only its rows and
  segment count (`e591cd39`); one helper keeps a wrapped row without its trailing spaces
  (`143df199`); the screen-row helpers live on the harness's `Scene` (`79ef3557`). Each with the
  transcript tests, the last with the full client suite twice, fmt and clippy green.
- 2026-11-08 (cycle 76, red at `79ef3557`): the time under the speaker's name (the user's report,
  with the short-turn choice "own second row"). Red: at 30 columns the old `name  time  text` row
  wrapped "Happy to / be here." and the test timed out on it. `wrap_runs` now takes the second
  row's lead (the time, muted) and aligns every later row after it, replacing the fixed indent; a
  turn that fits one row gets the time on a row of its own. The test waits for the narrow frame by
  the transcript panel's closing corner, since the first screen read after the resize was the wide
  frame clipped. Tests that found a turn by `name  time  text` - `speakers`, `events_refresh`,
  `reassign`, `reset_merges`, `turn_tints` and `turn_spacing` - now look for `name  text`, the same
  speaker-owns-text check; `reset_merges`' merged turn has no segments, so its `Speaker 2  00:00`
  became the name's row directly above a ` 00:00` row (a first full-suite run caught it).
  `turn_spacing` waits for the narrow frame the same way and expects the continuation one column
  in, where the time row starts. Verification: the turn-time test, and the full client suite
  twice, fmt and clippy green.
- 2026-11-08 (cycle 77, red at `0e6b0422`): a short name keeps the text's column (the user's
  report). Red: Speaker 2 renamed to "Bob" read `Bob  Happy to be here.`. The gap after a name is
  now as wide as the time's ` 00:05  ` less the name's width, at least two spaces, so the text on
  both rows starts in one column; the test renames through the prompt as a user would and checks
  that "Speaker 1", wider than the time, keeps its two spaces. The `speakers` and `events_refresh`
  waits for "Bob" and "Alice" now carry the widened gap. Verification: the turn-time tests, and
  the full client suite twice, fmt and clippy green.
- 2026-11-09 (cycle 78, red at `f6533aea`): the stenographer placeholder. Red: no product name and
  no tile - the test timed out waiting for "Speech-to-Text". The header is now three rows: the
  signed-off design's tile (`client/stt-tui/src/stenographer.rs`, two frames, the pen moving and
  the paper turning) at its left, in the accent role; right of it the product name (catalog key
  `app.name`, the browser's own title), the tab bar and a muted rule. The empty queue and history
  draw a six-row tile over their words, or the words alone where the panel is too short. While a
  task is RUNNING the main loop waits for messages only until the next 400 ms step is due, so a
  stream of events cannot stall the animation; otherwise it rests on the first frame. The pages
  moved two rows down, so tests that named fixed rows changed: `mouse` clicks the tabs on row 1,
  eleven columns right, and page rows two lower; `tab_keys` reads the tab row; `speakers_focus`'s
  panel rows are 4 and 5; `turn_tints` and `turn_time` find their rows by name. Verification: the
  stenographer test, the full client suite twice, fmt and clippy green, and the frontend's
  catalog readers (`QueueView`, `shared/ui`) green with the new key.
- 2026-11-09 (the user's question after cycle 78): in the browser the large stenographer does not
  follow the running task - the empty queue's always types (item 59), History's empty state and
  the loading states rest - while cycle 78 drew every TUI tile by the running task. The user chose
  "browser's rules + loading": two lines recorded under the stenographer line (`1b17bedb`).
- 2026-11-09 (refactors after cycle 78, approved by the user): one `fg` helper gives a foreground
  or, under `NO_COLOR`, no style, replacing the pattern at eight call sites (`7fa82c16`);
  `main.rs` imports `Instant` and `Duration` once (`36676940`). The full client suite twice, fmt
  and clippy green over both.
- 2026-11-09 (cycle 79, red at `36676940`): the large tile's browser rules. Red: on the empty
  History the large tile moved along with the header's while lecture.m4a ran. The view now knows
  whether a task runs (`typing`): the header's tile steps only then, the empty queue's always, the
  empty History's never; the main loop's timer runs while any tile on screen types. The test reads
  the large tile from where its rounded corners are and compares it once the header has moved,
  then waits for the empty queue's to change with nothing running. Verification: both
  stenographer tests, the full client suite twice, fmt and clippy green.
- 2026-11-09 (the user, after cycle 79): "We're missing a delete option in History" - answered that
  it is planned, line "A History row retries a FAILED or CANCELED task and deletes any task after a
  confirmation"; the user kept the order, the tab bar's indicators next.
- 2026-11-09 (cycle 80, red at `fc3893d2`): the header's connection and mode. Red: the first row held
  the product name alone, so "Connecting" never showed. Right of the name the row now reads
  `● Connecting` / `● Live` / `● Reconnecting` in the browser's colours (muted, the done status's,
  the problem status's) and, once the settings are read, `◐ Theme: system|light|dark`, the
  browser's labels (catalog keys `header.*`). The parts are left-aligned so the mode's columns
  come from the text alone: `status_line` gives the draw its line and the hit-test those columns,
  and a click there runs `cycle_theme`, the body the `t` handler had, which both now call.
  Verification: the header-status test, the full client suite twice, fmt and clippy green, the
  catalog Prettier-clean and the frontend's catalog readers green.
- 2026-11-09 (refactors after cycle 80, approved by the user): a `bold` helper writes no attribute
  under `NO_COLOR` (`fc863576`); the connection state is `Ui`'s own method (`c5f716b9`). The full
  client suite twice, fmt and clippy green over both.
- 2026-11-09 (the user's choice before cycle 81): a refused write shows "both" - the footer's write
  keys muted while not live, and a notice naming the refused action when one is pressed anyway.
- 2026-11-09 (cycle 81, red at `c5f716b9`): the write gate. Red: after the stream dropped the
  footer's `p` stayed in the accent. One `write_name` in `Ui` knows which actions write, by mode
  and screen: task commands the selected task allows, reorder, queue pause, delete (opening it and
  confirming it), upload (opening the dialog, and Enter only where it sends a file - it also opens
  folders), dismissing the fallback, the transcript's rename, edit, revert, merge, reset and
  assign, settings changes, and `t`. While the connection is not Live (connecting or reconnecting)
  such a key sends nothing and the notice reads "Not sent: <action>. Can't reach the app's
  service." (catalog `writes.*`); the footer draws those hints muted (`write_keys`, the dialogs'
  Enter left alone). The browser does not gate its header theme control; the line says every
  write, so the TUI's `t` is refused too. Verification: the write-gate test, the full client suite
  twice, fmt and clippy green, the catalog Prettier-clean and the frontend's catalog readers
  green.
- 2026-11-09 (refactors after cycle 81, approved by the user): which keys write lives in its own
  `writes` module (`06118f60`); the write list and the footer's write keys point at each other
  (`0df423f1`). The full client suite twice, fmt and clippy green over both.
- 2026-11-09 (the user's scope for the confirmation line): the dialog and four of its five cases now
  (delete, cancel a started task, merge, reassign with merges); restoring the default settings
  joins it with its own line, and the line ticks then.
- 2026-11-09 (cycle 82, red at `0df423f1`): one confirmation dialog. Red: `c` on the running
  lecture.m4a and the merging `m` both sent at once, so neither test saw its dialog. Every
  confirmation is now a `view::Dialog` drawn one way - the browser's question as the title, its
  sentence of what follows, and `Enter <confirm> · Esc Cancel` (catalog `confirm.*`). The queue
  asks before a delete (as before, now in this style) and before cancelling a RUNNING task or a
  PAUSED one that has started (the client's `Task` reads `startedAt`); a task never started is
  cancelled at once, as the browser does. The transcript holds a merge, and a reassignment in a
  task with merges, as `Pending` until Enter sends it; Escape puts the mark or the selected
  segments back. The write gate names the confirmation's own write. Existing tests answer the new
  dialogs: `commands` (cancel), `speakers` (both merges - the successful one and the refused one,
  which a first full-suite run caught - and its module doc), `delete_task` (the
  dialog's "Enter Delete task" for the old prompt's "Enter confirms"). The new transcript test
  first expected segment 1; the cursor keeps its place through the re-read on segment 3, as
  `reassign` already pins, so the expectation was corrected. Verification: both confirm-dialog
  tests, the full client suite twice, fmt and clippy green, the catalog Prettier-clean and the
  frontend's catalog readers green.
- 2026-11-09 (branch fix, found by cycle 82's second full run): `dialog_redraw` compared the queue
  before and after the upload dialog, but took its "before" as soon as the rows showed, while the
  harness's auto-answered startup settings read could still be in flight; since cycle 80 that
  answer adds `◐ Theme: system` to the header, so a late answer made "before" and "after" differ
  (run 2 failed, run 1 and every earlier run passed). The test now takes "before" once the header
  names the mode. No other test compares whole screens. Verification: `dialog_redraw` five times
  alone, and the full client suite twice.
- 2026-11-09 (refactor after cycle 82, approved by the user): answering a confirmation is `Ui`'s
  own method, `answer` (`7db25129`). The full client suite twice, fmt and clippy green.
- 2026-11-09 (cycle 83, red at `7db25129`): the queue's scope. Red: the snapshot's failed
  `call.ogg` was a queue row. `state::rows` now keeps the queue's ids whose task is QUEUED, RUNNING
  or PAUSED - a task that finishes leaves at once, even before the service's queue update - and
  History's rows are COMPLETED, FAILED and CANCELED tasks. So that those tasks stay retryable,
  History's `r` retries a FAILED or CANCELED row (half of the History retry/delete line, noted
  there), and the write gate covers it; `[r]etry` moves from the queue's footer to History's.
  Existing tests that pinned the old rule now pin the new one: `commands` (the cancelled task
  leaves the queue, and is retried from History), `queue_rows`, `queue_narrow` and `key_hints`
  (no failed queue row, no queue retry key), `theme_colours` (FAILED's colour read in History),
  `upload` (a completed upload leaves the queue without a transient COMPLETED row), and
  `wheel`'s comment; the user-run `journey` reads COMPLETED from History, since a failed task no
  longer stays in the queue to tell it apart. Verification: the queue-scope test, the full client
  suite twice, fmt and clippy green, the catalog Prettier-clean and the frontend's catalog readers
  green.
- 2026-11-09 (the user, "merged and updated"): `main` gained #116-#118, none of them touching a file
  this branch changes; asked whether to rebase now (which would orphan the commit hashes this log
  cites), the user chose to rebase when the item goes to review.
- 2026-11-09 (refactors after cycle 83, approved by the user): History's rows are `state::finished`
  (`8e8ded79`, renamed through the language server); the open list's selected task is `Ui`'s own
  method, `selected_task` (`635c23d6`). The full client suite twice, fmt and clippy green over
  both.
- 2026-11-09 (cycle 84, red at `635c23d6`): History's delete. Red: Delete on the failed call.ogg
  opened nothing. Delete or Backspace on any History row now opens the queue's `Confirm::Delete`
  dialog ("Delete call.ogg?", "Removes the task, its transcript and audio for good."); a
  `task-deleted` event removes the task from History's list as well as the snapshot, the
  selection kept on a row. The write gate covers it, and History's footer gains `[Del] delete`
  (muted offline). The browser's History words its dialog differently ("Delete task?"); the TUI
  uses one text for both lists, which names the file. Verification: the History-delete test, the
  full client suite twice, fmt and clippy green, the catalog Prettier-clean and the frontend's
  catalog readers green.
- 2026-11-09 (refactor after cycle 84, approved by the user): History's keys are `Ui`'s own
  `history_action`, and the wheel and the double click go through it as the key handler does
  (`35f26f35`); a first pass missed those two callers and the wheel and mouse tests caught it. The
  full client suite twice, fmt and clippy green.
- 2026-11-09 (the user on the single source of truth): "derived" - the footer asks the write rule
  itself; recorded as a line and done as its own cycle, since muting now follows the state.
- 2026-11-09 (cycle 85, red at `35f26f35`): the footer's muted keys from the write rule. Red: in
  History with the stream down, `r` was muted on the completed row too. `writes::hint_key` reads
  the press a catalog hint names (`[p]ause`, `re[v]ert`, `[Del]`, `[Shift-↑↓ or J/K]`) and
  `writes::hint_writes` asks `name` with it, so `hints::Muted` now takes a predicate instead of a
  key list and `view::write_keys` is gone. `Ui`'s mode and context computation became
  `input_mode`/`input_context`, the view carries them with the confirmation's write name, and
  `state::task_at` is shared so the view can find the selected row. Hints naming a list of
  navigation keys (`↑↓/j/k`, `PgUp/PgDn`) name no press and never mute. The test reads the
  History tab's two colours, which are swapped from the queue's - a first attempt read them the
  wrong way round and timed out waiting for a colour that could not come. Verification: both
  write-gate tests, the full client suite twice, fmt and clippy green.
- 2026-11-09 (cycle 86, red at `6400c54e`): History's search and status filter (the line's `/` and
  `f`, with the browser's rules and words). Red: `/` opened nothing. History's panel now carries a
  controls line - `Search: {query} · Status: {filter}`, an em dash while the query is empty - and
  the rows under it; when nothing matches it says "No tasks match your search and filter. Esc
  clears them." `state::history` filters the finished tasks by name (part, ignoring case and the
  spaces around it) and by `state::Status` (All, Completed, Failed, Canceled, `f` cycling them in
  the browser's order); every History reader - selection, retry, delete, the draw and the mouse's
  hit-test - reads that one function. `/` puts the keyboard in `Mode::Search`, where every
  character narrows the list as it is typed; Enter closes it keeping the query, Escape clears the
  query and the filter at once; Escape with the prompt closed does the same before it would go
  back. The controls line sits under the border, so the hit-test moved down a row; a first
  full-suite run caught `mouse`'s double-click still aimed at the controls row, and it moved too.
  Verification: the search test, the full client suite twice, fmt and clippy green, the catalog
  Prettier-clean and the frontend's catalog readers green.
- 2026-11-09 (cycle 87, red at `cfc88e20`): History's rows. Red: the fixtures' newer task did not
  come first. `state::history` sorts the finished tasks newest first (`finishedAt`, unknown last;
  the sort is stable). A row is now the browser's card in lines: the marker, name and status (in
  its group's colour), then `Oct 9, 2026 · 19:45` muted, then a stored error in the problem
  colour; `Task` reads `finishedAt`, `durationMs` and `error`, and the date and duration are
  written as the browser writes them (fixed month names, UTC; `mm:ss`, `h:mm:ss`), the date by
  days-to-civil arithmetic since no date library is a dependency. ratatui gives a `Row` one line
  unless told otherwise, so each row sets its height, and the mouse's hit-test counts those
  heights instead of assuming one row per task. Verification: the History-rows test, the full
  client suite twice, fmt and clippy green.
- 2026-11-09 (refactor after cycle 87, the user's call: "add chrono. no need to reinvent the
  wheel."): `chrono` writes History's finish date (`client/stt-tui/Cargo.toml`, the lockfile gains
  it), replacing the hand-written days-to-civil conversion; a History row's height is now the
  length of the lines it draws, so `history_lines` and the count cannot disagree (`ce495f2d`). The
  full client suite twice, fmt and clippy green.
- 2026-11-09 (cycle 88, red at `cd55b7f4`): a transcript opens with its header. Red (the test run
  against the committed branch with the change stashed): no "Loading transcript…", so it timed
  out. Enter on a History row now opens the transcript screen at once in a loading state -
  `Transcript::loading` carries the task, its finish date and duration from `history_meta` show as
  a header line under the title, and the resting large tile (the transcript half of the
  waiting-tile line) sits over "Loading transcript…"; the turns replace it. A failed read arrives
  as `Message::TranscriptFailed` and is shown in place - "cannot load the transcript: {error}" in
  the problem colour with "Enter retries" - and Enter reads again. The test's first run left the
  header behind once the turns arrived (red at the new assertion: no "Oct 9, 2026" on the loaded
  page), so the loaded page grew a one-row header above the speakers panel, shared with the
  waiting page through `header_text`. That row moved the Speakers panel down one, so
  `speakers_focus` now finds the panel's title row and aims at the two rows under it instead of
  pinning screen rows 4 and 5. The check that a test would go red first was done by stashing the
  implementation; the same test then passes. Verification: the
  transcript-header test, the full client suite twice, fmt and clippy green, the catalog
  Prettier-clean and the frontend's catalog readers green.
- 2026-11-09 (cycle 89, red at `46f1112f`): a task without a transcript gets its own page. Red (the
  implementation stashed against the committed branch): Enter on `call.ogg` opened a transcript and
  no "Failed while transcribing" ever showed. `Ui::open_task` routes an open by status - COMPLETED
  to the transcript (which asks for its turns), FAILED or CANCELED to `Screen::Status(task)`, and
  anything still pending to the same page, which says "This task is still in the queue." and asks
  for nothing. The page is titled with the file name (History's tab) and shows "Failed while
  transcribing at 04:58" from `phase` and the new `Task.transcribed_until_ms` (or "Canceled while
  ..."), the stored error, and `[r]etry puts it back in the queue`; `r` sends the retry POST and
  goes to the Queue, and `b` returns to the tab the status belongs to. Enter on a Queue row now
  opens that task too, so the waiting page is reachable. New catalog keys: `status.*`,
  `footer.status`, `help.status`. The first test run opened the newest History row instead of
  `call.ogg` and expected brackets the hint renderer strips, so the red was re-checked with the
  corrected test. Verification: the status-page test three times, the full client suite twice,
  fmt and clippy green, the catalog Prettier-clean and the frontend's catalog readers (QueueView,
  `src/shared/ui`) green.
- 2026-11-09 (three refactors after cycle 89, approved together): `state::ended` reads
  `FAILED | CANCELED` for the status page's tab, the screen `b` returns to and the page's own
  branch, and `state::finished` reads it too (`c7add25e`); `Screen::back` holds those targets
  beside `Screen::page` (`89c0ffd9`) - the first attempt derived it from `page()` and left `b` on
  History itself, which the `history` and `quit` tests caught; `draw_status` picks its failed or
  canceled catalog keys once instead of in both branches of the match (`9dca0f7b`). The full
  client suite, fmt and clippy green after each; behavior unchanged.
- 2026-11-09 (cycle 90, red at `4c3c3b94`): a transcript's task can be deleted from it. Red: Delete
  on the transcript showed no dialog, so the test timed out. `other_action` now opens
  `Confirm::Delete(task)` for `Action::DeleteTask` while a transcript is open, and `answer` sends
  `delete_task` as the rows do; a confirmation that came over a transcript sets the History screen,
  where the service's `task-deleted` takes the row away (a failed write's notice lands there too).
  The transcript footer lists `[Del] delete`. Verification: the delete test three times, the full
  client suite twice, fmt and clippy green, the catalog Prettier-clean and the frontend's catalog
  readers (QueueView, `src/shared/ui`) green.
- 2026-11-09 (the refactor after cycle 90, approved): the History-row reader left
  `history_delete.rs` and `transcript_delete.rs` for the harness (`1fef7b5a`, `history_row` beside
  `cell_marked`), so both tests read a row the same way. Behavior unchanged; fmt and clippy green.
- 2026-11-09 (`ce99d3dd`, found by that refactor's full-suite run): `Tui::wait_for` ran its
  predicate against the parser mid synchronized update, so the History page matched after its clear
  and two rows, and `transcript_delete` asserted on a half-painted screen (the suite reddened once;
  the test alone was green 12 of 12 afterwards). The reader now walks the frame markers and
  `wait_for_screen` reads only a completed frame, handing back the text or the scene it matched; a
  frame count and the open-frame flag share one lock. Verification: the failing test 12 times, the
  full client suite twice, fmt and clippy green.
- 2026-11-09 (cycle 91, red at `ce99d3dd`): a speaker's name can be reset to its default. Red: `R`
  on the focused speaker sent nothing, so the test timed out. `R` now resets the focused speaker's
  name while the Speakers panel has the keyboard - `PUT /api/tasks/3/speakers/0`
  `{"speaker":null}` through the new `reset_speaker_name` - and resets the task's merges on the
  transcript as before; the answer relabels the panel and the turn, and the turns are read again.
  Verification: the reset test three times, the full client suite twice, fmt and clippy green.
- 2026-11-09 (the refactor after cycle 91, approved): `rename_speaker` and `reset_speaker_name`
  folded into one `set_speaker_name(service, id, speaker, Option<&str>)` (`ce67298e`), null naming
  the speaker `Speaker N` again; the test's leftover assert, which its own wait already guaranteed,
  is gone. Behavior unchanged; the rename, reset and dialog tests green, fmt and clippy green.
- 2026-11-09 (cycle 92, red at `ce67298e`): a whole turn can be marked for a reassignment. Red: `A`
  was unmapped, so the wait for the panel's "(selected)" timed out. `A` marks every segment of the
  cursor's turn - the browser's per-turn selection - and unmarks them once all are, and `n` moves
  them in one request; the transcript footer names it `[A]ll of the turn`. Verification: the
  turn-mark test three times, the full client suite twice, fmt and clippy green, the catalog
  Prettier-clean and the frontend's catalog readers (QueueView, `src/shared/ui`) green.
- 2026-11-09 (the user's e2e run and the two decisions it confirmed): the run's e2e unit tests were
  55/55 green and its journey 57 of 59 specs, the one failure being the recorded
  `87-writes-wait-for-the-connection`, the one skip `18-macos-metal`, and one spec not run past the
  failure. `87` now signs in again after its restart (`b2dbd6aa`), as `13-restart` does: the
  restarted service keeps no session, so the old cookie was answered 401 and every `/api/events`
  reconnect 406 for the whole wait in app mode. A request body Ktor cannot read answers the
  contract's `400 invalid-request-body` instead of a 500 (`f5e4b0f8`): `InternalError` answers a
  `BadRequestException` as 400 with the contract's body and logs the conversion at info without
  quoting it; `SettingsUpdateSpec` pins an unknown settings key, and the HTTP contract names the
  code. Verification: the settings, internal-error and theme-mode route specs, `gradle check`
  (ktlint and every unit test), the e2e type check and Prettier. The journey run after these
  fixes is the user's; `docs/known-issues.md` lists neither issue any more.
- 2026-11-09 (the refactor after cycle 92, approved): the `A` handler's mark toggle moved into
  `Transcript::toggle_marked(&[i64])` (`d3736c12`); behavior unchanged, the turn-mark, reassign and
  guidance tests plus fmt and clippy green.
- 2026-11-09 (cycle 93, no red at `d3736c12`): Escape in the reassign confirmation. The dismissal
  already worked (`Transcript::dismiss` returns the segments, sends nothing, closes the dialog), so
  the first run was green: `confirm_dialog.rs` gains the pin the line lacked, not a behavior change.
  It merges first so the task has merges, marks the whole turn with `A`, asks with `n`, presses
  Escape, and checks the dialog is gone, the selection is back, and no request arrived.
  Verification: the confirmation tests three times, the full client suite twice, fmt and clippy
  green.
- 2026-11-09 (cycle 94, red at `b7549e36`): a segment edit is trimmed before it is sent. Red: the
  PUT carried the prompt's padding (`"  Hello there.  "`), though the contract says clients trim
  first. `stt-client`'s `edit_segment` now sends `text.trim()`, as `rename_speaker` already did;
  the new test in `segment_edit.rs` pads the prompt twice over and expects the trimmed body and the
  trimmed turn. Verification: the segment-edit tests three times, the full client suite twice, fmt
  and clippy green.
- 2026-11-10 (cycle 95, red at `0444f733`): an unchanged segment edit sends nothing. Red: Enter on
  the untouched prompt still sent `PUT …/segments/1 {"text":"Good morning."}`. `SubmitPrompt` now
  closes the prompt and returns when the draft trims to the text the cursor's segment already has;
  the test then makes a real edit and reads the first request the service saw, which pins the
  first one's absence deterministically. Verification: the segment-edit tests three times, the
  full client suite twice, fmt and clippy green.
- 2026-11-10 (cycle 96, red at `a795182b`): a refused segment edit keeps the draft with the
  service's reason. Red: the 400 left the prompt closed, so the draft was gone. `SubmitPrompt` now
  sends the edit through `edit`, whose refusal sends `Message::EditRefused(task, segment, draft,
  reason)`: the open transcript puts the draft back in its prompt and the notice reads
  `edit failed: <the service's message>`; the turn keeps its text. Verification: the segment-edit
  tests three times, the full client suite twice, fmt and clippy green.
- 2026-11-10 (the user's decision on line 485's line breaks): "line breaks become spaces" has no
  route in the TUI - the edit prompt is one line and Enter submits, and no bracketed paste arrives
  to carry a newline - so that clause is the browser's (the frontend's text area normalises it) and
  the line's remaining clause is the empty segment's `[empty]`.
- 2026-11-10 (the user's `pnpm --dir frontend test`, run in the worktree): 44 test files and all
  312 tests passed, and Prettier reported every file in its style. The run printed React's `act()`
  warnings from several transcript and queue tests, jsdom's `Not implemented:
  HTMLMediaElement.pause()`, and one duplicate-key warning for segments sharing a time range;
  `docs/known-issues.md` now records the duplicate key and the warning noise.
- 2026-11-10 (the refactor after cycle 96, approved): `Message::EditRefused` is a struct variant
  (`{ task, segment, draft, error }`), so its construction and its handling read by name rather
  than by position (`65a1dd0b`). Behavior unchanged; the segment-edit tests, the full client
  suite, fmt and clippy green.
- 2026-11-10 (cycle 97, red at `65a1dd0b`): an empty segment shows `[empty]`. Red: a segment the
  service answered with `""` drew as nothing, so the wait for `[empty]` timed out. The view draws
  the new catalog key `transcript.empty` for a segment with no text - muted, or on the cursor's
  accent tint when it is current - so the row stays readable and selectable. Verification: the
  segment-edit tests three times, the full client suite twice, fmt and clippy green, the catalog
  Prettier-clean. This ticks line 485: its line-breaks clause is the browser's (the user's decision
  2026-11-10), the TUI's prompt being one line.
- 2026-11-10 (cycle 98, red at `8a9a7eb1`): History follows the events while it is open. The red ran
  on the journey's failure: `gradle tuiJourney` uploaded `jfk.wav`, the run finished in 3 s - a
  direct probe of the installed service showed the task COMPLETED - and the TUI's History still said
  "No completed tasks yet" after 600 s, because the page was a `GET /api/tasks` snapshot fetched
  when it opened (and the page only switched once that answer arrived). Green: History's rows are
  the stream snapshot's finished tasks, which `state::apply` keeps current, so the page opens at
  once and takes a `task-updated` in place - the browser's list, which the backend feeds with a
  complete snapshot (`EventsRoutes.kt`). `listed`, `Message::History`, `open_history` and the
  catalog's `history.failed` are gone. The pty tests that answered the fetch (20 files) now read
  the rows from the snapshots they already sent; `wheel.rs`'s two completed tasks and
  `history_rows.rs`'s newest-first date moved into the snapshot, `stenographer.rs` sends a
  queue-only one where it wants an empty History, the shared `open_transcript` helper no longer
  answers a list, and `commands.rs` moves onto the CANCELED row (History's newest-first order puts
  it third). Verification: the new test red then green, the full client suite (152 tests, the
  journey ignored) green, fmt and clippy green, the catalog Prettier-clean. Line ticked.
- 2026-11-10 (cycle 98, after the fix): `gradle tuiJourney` is green end to end - the upload is
  queued, the task completes, History shows its COMPLETED row as it lands (the live list this
  cycle added), Enter opens the transcript with JFK's line, and Ctrl-C quits - 5.6 s. The journey
  pressed `q` to quit from the transcript, where `q` walks one page back, so it now presses Ctrl-C,
  which quits from every page (`5bbaddae`).
- 2026-11-11 (cycle 99, the user's decision the same day: one line, two suites - option C): the
  theme's e2e line. `e2e/tests/67-theme.spec.ts` sets light and dark through the header's control
  and checks each step: `GET /api/settings` reports the mode, `document.body` paints
  `colors.background[mode]` from `ui-theme/colors.json`, and after `page.reload()` the header shows
  the stored answer and the page paints it again (the spec ends on `system`, and `themeReset` guards
  the next spec). The first run failed on the spec's own defect: it captured
  `page.evaluate(...)`'s promise once, so every poll read the pre-click background; the paint is a
  function now, and the second run passed in 15 s. The TUI half is
  `client/stt-tui/tests/journey.rs`'s `the_tui_draws_the_mode_the_service_stored`, run by
  `gradle tuiJourney` (where a real service and a pty already exist): it stores light with the
  whole-settings `PUT` the browser makes, starts the TUI on that service, reads the panel border in
  the light `border` token, then stores dark from outside and waits for the live repaint in the dark
  token. Both halves pin behaviour that was already there (green on arrival), which is what an e2e
  line of an approved feature is for. Verification: the spec against a dev-mode service started by
  `E2E_BACKEND_CMD` (`sh <installDist launcher>`; the sandbox has no `/bin/sh` for the launcher's
  shebang) - 1 passed, Prettier-clean, `tsc -p tsconfig.json` and `check-playwright.ts` green;
  `gradle tuiJourney` - 2 passed (the upload journey and this test), 5.8 s. Line ticked.
- 2026-11-11 (cycle 100, red at `3aef5cc0`): the connecting half of the waiting-region line. Red: a
  pty test opened the stream and sent no snapshot; the screen showed the empty queue ("No tasks
  yet") with no "Connecting…" anywhere. Green: while the view holds no snapshot the browser's
  loading region sits above the page - the resting large tile over `connection.connecting`
  ("Connecting…", the new catalog key) - and the page still draws under it, as the browser's
  `ConnectingRegion` does above its routes; once a snapshot arrives the region goes. The page's
  dispatch moved into `draw_screen`. The test also pins the region's rest: one and a half seconds
  later the screen is identical (it waits for the settings read's theme control first, so the
  header is whole). Verification: the connection tests, the full client suite (153 tests), fmt and
  clippy green, the catalog Prettier-clean, `gradle tuiJourney` 2 passed. Line ticked.
- 2026-11-11 (cycle 101, red before the code): a transcript's playback. Red: with the transcript's
  `Space` unmapped (the arm commented out), the pty test pressed it and no `GET /api/tasks/3/audio`
  arrived within 20 s, so the state the line asks for did not exist. Green: `Space` in a transcript
  reads the clip once (`GET /api/tasks/{id}/audio`, the credential, the FLAC the service stores),
  the player decodes it with `claxon` on a thread of its own and writes it to the default output
  through `cpal` (ALSA on Linux, WASAPI, CoreAudio), and a second `Space` pauses and a third
  resumes from the playhead. `STT_AUDIO_SINK=null` is the silent real-time sink the pty tests and
  the journey use - no sound card on a runner - walking the head from the wall clock exactly as the
  output callback counts frames; a decode or output failure shows `audio.failed` in the bar instead.
  The bar is the transcript's last row: the state (`▶`/`⏸`), the playhead over the task's length in
  the browser's `mm:ss`, and a thin `━`/`─` progress line in the accent and muted roles; the loop
  wakes on its own while a clip plays, so the head moves without an event. The footer names
  `[Space] play`. Dependencies: `cpal` 0.18 and `claxon` 0.4 in `stt-tui` (the lockfile follows),
  `libasound2-dev` on the Linux client and Linux e2e CI jobs (macOS and Windows need no package),
  and the README's Linux prerequisites name it. The footer test now reads the wrapped transcript
  hints by their words: the longer line wraps at 200 columns. Verification: the test red and green,
  the full client suite (154 tests), fmt and clippy green with the ALSA development files on
  `PKG_CONFIG_PATH` (this session's shell predates `alsa-lib` in `devenv.nix`; the user's
  `direnv reload` reaches their terminal, not this sandbox), `gradle tuiJourney` 2 passed, the
  catalog and README checks green. Real sound output is the user's to hear; the next lines add the
  playing segment's highlight and seek, `p`, `Space` on a History row, the no-device notice and the
  journey's real-time playback.
- 2026-11-11 (`2ae253fa`, a branch fix, unlogged until now): the user reported the audio playing
  too fast. The player now reads the clip's header for its sample rate and re-files the decoded
  frames to the device's rate as the output callback asks for them, instead of assuming the two
  agree; the callback's layout is covered by unit tests. The pty tests and the journey run the
  silent sink, so the user's ears verify the real output.
- 2026-11-11 (cycle 104, red at `2ae253fa`): `R`'s two resets. The user reported "reset merge
  doesn't work". The key has a second meaning - on the Speakers panel it resets the focused
  speaker's name - and with the panel focused (Up from the first segment) or already at its
  default name it wrote nothing visible, so a body reset looked broken. Green: without merges the
  body says `no merges to reset`; on the panel a speaker already named by default is named in a
  notice instead of a write that changes nothing; the footer splits by focus - `[R]eset merges`
  on the body, `footer.speakers` with `[R]eset name` on the panel (new catalog key). The journey
  gained `r_resets_the_merges_the_service_reports`: a real service with two speakers merged under
  one group id, the TUI resets, the service's `GET` returns `hasMerges: false` and both names.
  Verification: the reset tests and the footer test green, the full client suite (two full runs),
  fmt and clippy green with ALSA on `PKG_CONFIG_PATH`, `gradle tuiJourney` 3 passed, the catalog
  Prettier-clean. Line ticked and a discovered line added from the same message: a tab's shortcut
  letter is underlined in the accent role only when it would act - not on the open tab, not while
  a dialog or prompt holds the keyboard.
- 2026-11-11 (cycle 105, red at `ba54d34a`): the tab bar's shortcut mark. Red: the open tab's
  letter was underlined too, and the mark was the tab's own colour (accent on the open tab, muted
  on the others) rather than the accent everywhere. Green: `draw_tabs` marks a tab's first letter
  only where a shortcut can open it - not the open tab, and only in `input::Mode::Normal` with the
  key reference closed, which is exactly when `input::action` maps Shift+letter to a page - in the
  accent role underlined, the footer's mark for a live key; `NO_COLOR` still writes no attribute.
  The pty test checks the mark's colour against the accent token, then opens the upload dialog and
  the key reference and requires no mark, and Escape puts it back. The full suite caught a branch
  defect this cycle: `turn_time.rs` had been red since the player bar took a row - it asked a
  24-row screen for the last turn's time and read the *open* tab's accent as "muted"; it now uses
  the `textMuted` token and 26 rows (`83713d85`, its own commit). Verification: 155 client tests in
  two full runs, fmt and clippy green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-11 (cycle 106, red at `982235ed`): the playing segment and its seeks. Red: `[`/`]` did
  nothing and no segment was filled. Green: the segment the playhead is in is filled in the accent
  role with `accentText`, as the browser fills its current segment (the client now reads a
  segment's `startMs`); the cursor walks with the playhead (`Transcript::track`, one segment
  mapping kept through a gap) until a key moves the cursor; `[`/`]` seek five seconds, clamped;
  Enter on a transcript's segment body seeks to the cursor's segment while the clip plays and
  plays from it otherwise - marked segments and the Speakers panel keep Enter's assign and rename,
  so `pending_from` carries the start through the clip's first read. Two defects the pty test
  found: a redraw could track a playhead one command stale, overwriting the cursor the seek had
  just set - so `Player::seek` moves the shared playhead at once, before the audio thread takes the
  command; and the test itself leaned on the real-time sink, whose 9.6 s borderline made the
  cursor's segment and the playhead's differ by a frame. New `STT_AUDIO_SINK=frozen` leaves the
  playhead where a seek put it (the audio thread only reads commands), so this test - seeks, fills,
  the follow, Enter - reads no clock at all and runs in 0.5 s; the real-time `null` sink still
  carries the line that pins the advance itself. A 20 s silent FLAC fixture (`playback-20s.flac`)
  gives the five-second seeks room. Verification: the new test red then green, 156 client tests in
  two full runs, fmt and clippy green, `gradle tuiJourney` 3 passed. Line ticked. The user's
  sound is still the ears' check; the next playback lines are `p`, `Space` on a History row, the
  no-device notice and the journey's real-time playback.
- 2026-11-11 (the user's approval after cycle 106, all four): refactors. `Ui::move_playhead` moves
  the playhead, tracks the cursor and starts tracking, shared by `[`/`]` and Enter;
  `Message::Audio` carries the position to play from, so the `pending_from` field and its reset go -
  these two interleave in the same functions and share `84731244`. The tests read a cell's colours
  through `terminal::style`, shared by the playing-segment and turn-time tests (`0e93a0ea`). The
  player's sink is `Sink { Device, Null, Frozen }`, parsed once from `STT_AUDIO_SINK`, replacing
  the two flags whose `null` meant "null or frozen" (`597032f7`). Verification: 156 client tests in
  two full runs, fmt and clippy green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 107, red at `3c9499e0`): the transcript footer's merge reset and `n`. The user
  asked for no `Reset merges` while there are no merges, and what `n` is: it assigns the marked
  segments to a new speaker, the browser's New speaker action. Red: the no-merges transcript's
  footer still showed `Reset merges` and no `new speaker`. Green: `footer_key` picks
  `footer.transcript-no-merges` (new catalog key, the transcript's hints without the reset) while the
  service's `hasMerges` is false and `footer.transcript` once it is true; both name `[n]ew speaker`.
  `reset_merges.rs` checks the hint absent before the merges and back with them; `footer_wrap.rs`
  wraps the footer a merge-less transcript actually shows. The session's environment lacked ALSA
  after the dev output was collected; the user landed `pkgs.alsa-lib` on `main`'s `devenv.nix`
  (#124), so `PKG_CONFIG_PATH` carries it with nothing set by hand (`806d9a44` corrected this
  branch's comment: `cpal`, not `rodio`). Verification, on `3c9499e0` with the cycle's changes not
  yet committed - the tree committed as `3c33ae9b` (`ec041adc` after the rebase onto `main`): the
  footer, wrap, reset and speaker-reset tests green; one full client run of 161 passed, its one
  failure the next cycle's red test (`opening_another_tab_stops_the_playback`), written during
  that run - the run before it did not compile, caught mid-edit; fmt and clippy green;
  `gradle tuiJourney` 3 passed. Two lines ticked.
- 2026-11-12 (the user: "You can now rebase onto `main`"): rebased onto `main` at `17531a56` (items
  95 and 97, the docs for the Rust backend rewrite, and #124, which took this branch's `alsa-lib`
  line). One conflict: `devenv.nix`, where `main` already carries the line with the corrected
  comment; the branch's original commit resolved to it and `806d9a44` dropped as already upstream.
  `ci.yml`, `build.gradle.kts`, `docs/known-issues.md`, the `87` spec, `SettingsView` (with its
  test) and `client.ts` merged on their own and were checked rather than trusted: both ALSA steps
  and all three journey steps are in the rewritten workflow, and the settings write keeps
  `settingsUpdateOf` with `themeMode` beside item 95's changes. One branch defect surfaced:
  `main`'s no-Python check reads the roadmap as a live document, and the 2026-11-07 entry named the
  checks of the time by their old name - reworded (`ac747970`). Verification, on `ac747970` (the
  docs node tests on its tree just before it was committed): the docs package's node tests 44/44
  with the links, architecture, exceptions and Windows checks; frontend `tsc`, 315 tests in 44
  files, Prettier; backend `gradle check` BUILD SUCCESSFUL; e2e `tsc -p tsconfig.json`; client fmt,
  clippy and 161 tests in two full runs; `gradle tuiJourney` 3 passed. The red test of the next
  cycle (playback stops when another tab opens) waited in a stash across the rebase.
- 2026-11-12 (cycle 108, red at `45d23d05`): another tab stops the transcript's playback. Red: on
  the frozen sink a clip started in the transcript, the queue's tab opened and the transcript opened
  again still showed it playing (`⏸`). Green: `open_page` pauses the player when it leaves a
  transcript; a transcript opened again is a new screen, so nothing walks the cursor, and its clip
  stands where it stopped - Space plays it on without reading `/audio` again (the test checks no
  request arrives). Backing out of a transcript with `b` or Escape is left to the edge line that
  covers leaving the transcript and playing another task. Verification, on `45d23d05` with the
  cycle's changes not yet committed - the tree committed as `a79a93df`: the playback tests green;
  client fmt and clippy green; `gradle tuiJourney` 3 passed; six full client runs, four clean at
  162 tests and two - both in the cycle's first gate - with one failing test each, whose name the
  summary did not keep. Not reproduced since; every later full run keeps its log so the next
  occurrence is named. Line ticked.
- 2026-11-12 (the user's approval after cycle 108, both): refactors. The playback tests start from
  one harness opener, `open_transcript_playing(sink, clip)` (`9d16f42a`; the playback tests green
  on its tree). The second - one transcript footer instead of two near-copies - became the shape
  the user's next request needed: every hint is a catalog entry of its own (`hint.*`), and
  `footer.rs` lists each page's, dialog's and the Speakers panel's hints in order, each with the
  rule for when its key can act; the footer shows those that can, the key reference lists them all,
  and `hints::rows` takes the texts. The merge reset's rule replaces `footer.transcript-no-merges`
  (`c6fb82d6`; on its tree before it was committed: 162 client tests, clippy, frontend 315 tests,
  the catalog Prettier-clean, `gradle tuiJourney` 3 passed). The user also asked that `assign` and
  `new` - and every other hint - not appear when it cannot be used. An audit of each key against
  its handler found the transcript, Speakers panel, queue, History and upload dialog offering keys
  that cannot act, or naming an action the key does not take (`p` on a paused task resumes,
  `Space` on a paused queue resumes it, Enter without marks plays): five lines record them, one per
  surface. Muting the write keys while the stream is down stays as it was - the browser greys such
  controls rather than hiding them, and pressing one says why it was refused.
- 2026-11-12 (cycle 109, red at `c6fb82d6` with the test and the harness change uncommitted): the
  transcript's footer. Red: with nothing marked the footer offered `assign` and `new speaker`, and
  `revert` on an unedited segment; a one-segment, one-speaker transcript offered `merge` and the
  page keys. Green: rules in `footer.rs` - `assign` and `new speaker` with marked segments, a new
  `[Enter] play from here` without (cycle 106's Enter), `revert` on an edited segment, `merge` with
  two speakers, paging and jumping with two segments, the cursor's keys with a segment, `rename`
  with a speaker. `transcript_hints.rs` holds both cases; the harness opens a transcript on given
  turns (`open_transcript_with`); `footer_wrap.rs` lists the hints `turns.json` now shows, and
  `reset_merges.rs` no longer expects `new speaker` with nothing marked - the hint test pins that
  wording where it applies. Verification, on `c6fb82d6` with the cycle not yet committed - the tree
  committed as `1af85c91`: 164 client tests in a full run, clippy green, `gradle tuiJourney` 3
  passed, the catalog Prettier-clean. Line ticked.
- 2026-11-12 (cycle 110, red at `4c7e408f` with the test uncommitted): the Speakers panel's footer.
  Red: on a default-named speaker the panel offered `Reset name`, and with a segment marked it still
  named Enter `rename`. Green: `Transcript::focused_renamed` answers whether the focused speaker
  has a name of its own - the footer's rule and `R`'s own check now share one default-name
  function - and the panel's list offers `Enter rename` without marks, `Enter assign` with them,
  and `merge` with two speakers. `speaker_reset.rs`'s default-name test waited for `Reset name`
  on that speaker; it now waits for the panel's footer without it, and `R` pressed anyway still
  says the name is its default. Verification, on `4c7e408f` with the cycle not yet committed - the
  tree committed as `a3370a73`: 165 client tests in a full run, clippy green, `gradle tuiJourney`
  3 passed. Line ticked.
- 2026-11-12 (cycle 111, red at `9370ed1f` with the test uncommitted): the queue's footer and
  `theme`. Red: before the stored settings were read the footer offered `theme`, and `on the
  upload area` showed with the area unfocused. Green: rules from the queue's own handler - `pause`
  or a new `[p] resume` by what `p` sends the selected task, `cancel` when the status allows it,
  `reorder` for a task with a neighbour (the browser reorders every pending task),
  `queue pause` or a new `[Space] queue resume` as the queue stands and neither on the upload
  area, where Space opens the dialog, `on the upload area` only there, `move` with a row; every
  list's `theme` waits for the stored settings `t` cycles from. `queue_hints.rs` walks the states
  (running, upload area, paused task, paused queue, empty queue); `footer_wrap.rs` drops the
  upload-area hint from the queue it sees, and `help.rs` waits for the drawn queue rather than
  the connecting frame, whose footer now starts at `upload`. Verification, on `9370ed1f` with the
  cycle not yet committed - the tree committed as `88151de7`: 167 client tests in a full run,
  clippy green, `gradle tuiJourney` 3 passed, the catalog Prettier-clean. Line ticked.
- 2026-11-12 (cycle 112, red at `9edfffcf` with the test uncommitted): History's footer. Red: on the
  completed `interview.mp3` the footer offered `retry`. Green: `open` and `delete` with a selected
  row, `retry` on a failed or canceled one, `move` with two rows - of the list as its search and
  filter show it, newest first. `View::history_rows` and `history_task` give that list one source:
  the rules use it, and so does the footer's write-muting, which had read `state::finished` -
  unfiltered and unsorted, so it could judge another row than the keys act on (a defect found on
  the way, fixed in this cycle by using the same helper). `history_hints.rs` walks a completed row,
  a failed one, a search nothing matches and a search one row matches - waiting after Escape,
  since an Escape with the next key right behind it reads as Alt and that key. `footer_wrap.rs`
  drops `retry` from the completed row's list, and `write_gate.rs` checks the completed row offers
  no `retry` (it had checked it unmuted) and waits for the failed row's to appear, muted.
  Verification, on `9edfffcf` with the cycle not yet committed - the tree committed as
  `a40c892e`: 168 client tests in a full run, clippy green, `gradle tuiJourney` 3 passed. Line
  ticked.
- 2026-11-12 (cycle 113, red at `ebb791fd` with the test uncommitted): the upload dialog's footer.
  Red: on a file the footer still offered `→ open folder`. Green: rules from the dialog's own
  handler - `up` while the shown folder has one above it (the root has none), `open folder` on a
  folder or `../` while the file list has the focus (on an option Right changes the option), `move`
  with two rows, `open or upload` with a row or a typed path; `UploadDialog::selects_folder`
  answers the folder question. `upload_hints.rs` walks `../`, a file, a folder and an option row,
  then an empty folder and `/`. The audit's other lists act in every state their footer shows:
  Status, Settings and the download dialog keep their hints. Verification, on `ebb791fd` with the
  cycle not yet committed - the tree committed as `d5464a4c`: 171 client tests in a full run,
  clippy green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 113): refactor `57cd28fa` - `terminal::footer_hints`
  and `offers` replace the five copies in the hint tests; the shared one reports no hints until a
  panel is drawn. Verification, on `d04b000e` with the refactor not yet committed - the tree
  committed as `57cd28fa`: the nine hint tests and 171 client tests in a full run, clippy green.
  The user also pointed out that the browser has no hint footer, so "the browser greys disabled
  controls" did not carry over to the write keys that stay muted while the stream is down. The
  standing choice is the user's own (2026-11-09, "both": muted in the footer, and a notice when
  pressed); whether the newer rule - no hint that cannot be used - replaces it is put to the user.
- 2026-11-12 (cycle 114, red at `e4cda3eb` with the test uncommitted; the user chose "hide" the
  same day): the write keys while the stream is down. Red: reconnecting, the queue's footer still
  showed `pause`, `cancel` and `queue pause`, muted. Green: the footer leaves out every hint the
  write rule picks while the stream is not live - the rule itself is unchanged, so a completed
  History row still offers no `retry` and a failed one offers it only while live - and keeps the
  keys that still act; pressing a write key anyway still names the refused action and the reason.
  `hints::rows` and `render` lose the muting they no longer need. `write_gate.rs` checks the
  footer through the shared `offers` helper: a failed row's `retry` and `Del delete` leave with
  the stream, the queue's writes and `theme` leave and come back with a new snapshot. The ticked
  lines of 2026-11-09 stay as they were written; this line records the change. Verification, on
  `e4cda3eb` with the cycle not yet committed - the tree committed as `a2601d0a`: 171 client tests
  in a full run, clippy green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 114): refactor `fc134e26` - `footer::shown` leaves the
  write keys out while the stream is not live, its write place built beside the other rules
  (`write_place`), so one module decides which hints show and the drawing only renders them. The
  key handler still builds its own place from the screen's state, which a `View` does not reach.
  Verification, on `76bf7b20` with the refactor not yet committed - the tree committed as
  `fc134e26`: 171 client tests in a full run, clippy green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 115, red at `dc949d24` with the test uncommitted): marked segments, the user's
  report that "N segment(s) selected" named how many but nothing named which. The browser's
  reassign mode puts a checkbox before every segment (and every turn); the TUI drew nothing. Red:
  after `a` the guidance said "1 segment(s) selected" and `Good morning.` looked as before. Green:
  while any segment is marked, every segment shows a box before it - `☑` in the accent on a marked
  one, `☐` muted on the others - and the boxes go with the last mark (`Transcript::segment_marked`
  answers per segment). `A` ticks every box of the turn, so the turn needs no box of its own.
  `marked_segments.rs` walks `a`, `A` and `A` again; the guidance and reassign tests still pass.
  The `p` cycle, started before the report, waits: its harness split (`open_transcript_on`) was in
  the working tree during this run and is committed with that cycle. Verification, on `dc949d24`
  with the cycle and that split not yet committed: 172 client tests in a full run, clippy green,
  `gradle tuiJourney` 3 passed; the cycle's own files committed as `d509f42d`. Line ticked.
- 2026-11-12 (the user, after cycle 115): two reports. "After reassigning speakers, it's not clear
  how it can be undone" - the service makes a reassignment permanent (and the merges of the task
  with it), so no frontend can undo one; the user wants a reset to the original state, before all
  merges and reassignments, and reopens that with the root agent - recorded in
  `docs/known-issues.md` (`54d94291`), item 67 implements what the browser has. "It's not obvious
  that the user needs to go all the way up to select a speaker" - the user chose the browser's
  "Move to…" picker over better guidance; the discovered line above records it.
- 2026-11-12 (cycle 116, red at `2b37a1f6` with the test uncommitted): the "Move to" list.
  `reassign.rs` now opens it: `a`, then Enter on the transcript - red, the screen kept the
  transcript with no list. Green: with segments marked, Enter on the transcript opens "Move N
  segment(s) to" over it - New speaker, then every speaker, the cursor's row highlighted; Up and
  Down choose (`Mode::Choose`, as a settings dropdown), Enter moves the marked segments there (the
  `writes.assign` the stream gate hides offline), Esc closes it with nothing sent. Its footer
  reads `↑↓/j/k move · Enter move there · Esc cancel`; the transcript's footer names Enter `move
  to…` (was `assign`), the guidance "press Enter to choose where they go" (was "on a speaker to
  move them there"); the Speakers panel's Enter still assigns to its speaker. `transcript_hints.rs`
  and `mark_guidance.rs` follow the new wording; `reassign.rs`'s `n` half moves the cursor to
  Speaker 2's segment itself, since Enter no longer leaves it there. Verification, on `2b37a1f6`
  with the cycle not yet committed - the tree committed as `e0e1dacb`, with the `p` cycle's
  uncommitted harness split beside it: 172 client tests in a full run, clippy and `cargo fmt
  --check` green, `gradle tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier
  over `ui-text/en.json` clean. Line ticked.
- 2026-11-12 (the user's approval after cycle 116): refactor `93af0af8` - `Transcript::marking`
  answers whether any segment is marked, for the seven spelled-out checks in `transcript.rs`,
  `view.rs`, `footer.rs`, `writes.rs` and `main.rs`. Verification, on `d80edff8` with the refactor
  not yet committed and the `p` cycle's harness split stashed - the tree committed as `93af0af8`:
  172 client tests in a full run, clippy green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 117, red at `93af0af8` with the test uncommitted): `p` plays the cursor's
  segment alone, as the browser's Play-segment control (`docs/specs/edit-transcription.md`): from
  its start, stopping at its end back at its start; `p` while it plays stops it there, as the
  browser's Stop. A pause keeps the window and resuming finishes it; a seek ends it, as in the
  browser. `segment_playback.rs` (frozen sink): `l` to the second segment, the footer offers `p`,
  `p` reads `/audio` and the bar stands at `0:02` playing, `p` again stops it at `0:02`. Red: the
  footer offered no `p`. The frozen sink never reaches an end and a seek ends the window, so the
  stop at the end is the player's own unit test,
  `a_segment_played_alone_stops_at_its_end_back_at_its_start`: the device callback over a window
  of two frames writes them, then silence, and stands back at the start. It was written after
  `Shared::advance`; with the window branch disabled it failed as expected (`[0.5, 0.5, 0.5, 0.5]`,
  the next frames sounding), and passed restored. Green: the player keeps the window
  (`Command::Play { until }`, `Command::PlayWindow`, `Player::window`), and one `Shared::advance`
  stops it for the device's callback and the silent sink's tick alike; `Segment` reads `endMs`;
  `hint.play-segment` ("[p]lay segment") on a segment. The harness split (`open_transcript_on`)
  goes in with it. Verification, on `93af0af8` with the cycle not yet committed - the tree
  committed as `65b53147`: 174 client tests in a full run, clippy and `cargo fmt --check` green,
  `gradle tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over
  `ui-text/en.json` clean. Line ticked.
- 2026-11-12 (the user's approval after cycle 117): refactor `edd00bea` - `read_clip` reads a
  task's clip for Space, Enter on a segment and `p`, which each spelled out the same `/audio`
  fetch; their loaded checks differ (Space resumes on the clip id alone) and stay as they were.
  Verification, on `fc3d0852` with the refactor not yet committed - the tree committed as
  `edd00bea`: 174 client tests in a full run, clippy green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 118, red at `edd00bea` with the test uncommitted): `Space` on a History row.
  `history_playback.rs` (frozen sink): from the transcript `b` returns to History, whose footer
  offers `Space play`; Space reads task 3's `/audio`, the row reads `♪ playing`, no command is
  sent; Space again pauses it and the mark goes. The first red run waited on `[2 History]` and
  `Space play`, which the transcript shows too, and passed that wait before `b` had acted; the
  test now waits on History's search line. Red: History's footer offered no `Space play` - Space
  there fell through to the queue's pause, a key History's footer never offered. Green: History's
  key handler sends Space to `play_history` - the selected row's clip from the start, paused and
  resumed by Space, another row's Space plays that one instead - a clip arriving on History plays
  there, `View::clip` lets the row show `history.playing` in the accent, and `HISTORY` offers
  `hint.play` on a row. The browser's History has no playback; this line is the TUI's own (the
  playback amendment). Verification, on `edd00bea` with the cycle not yet committed - the tree
  committed as `5add69b8`: 175 client tests in a full run, clippy and `cargo fmt --check` green,
  `gradle tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over
  `ui-text/en.json` clean. Line ticked.
- 2026-11-12 (the user's approval after cycle 118): refactor `0bf9b9c5` - `toggle_clip` pauses or
  resumes a task's own clip, for Space in a transcript and on History; Space in a transcript still
  paused another task's clip, kept in `play_pause`. Verification, on `69db2811` with the refactor
  not yet committed - the tree committed as `0bf9b9c5`: 175 client tests in a full run, 174
  passed; `settings_dropdown.rs`'s `a_dropdown_lists_the_options_and_chooses_one_and_escape_closes_it_alone`
  failed once and passed three runs alone. Its screen named the cause, a race in the test: the
  Language row shows the value being saved before the service answers, so the wait on "Language
  … es" passed while the write was in flight, and the next Enter opened the dropdown from the
  stored value (`✓ de german`, not `✓ es`). Likely the unnamed intermittent failure of cycle 108;
  the open line "Settings show … 'Saving…' while a write runs" gives the test a state to wait on.
  Clippy green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 119, red at `0bf9b9c5` with the test uncommitted): the edge line's third part,
  split from it word for word. `playback_stops.rs` (frozen sink): from a playing transcript `b`
  returns to History - red, the row still read `♪ playing`, since `b` and Esc left the clip
  playing (only the tabs stopped it, cycle 108). Green: `stop_transcript_playback`, shared with
  `open_page`, stops it on Back too. The test then plays interview.mp3 on with Space (no read)
  and call.ogg with Space on the next row (`/tasks/4/audio`, interview.mp3 stops while it is read)
  - green from cycle 118's `play_history`. Extended before the commit, with interview.mp3's
  transcript opened while call.ogg plays: red, Space only paused call.ogg and read nothing (the
  "another task's clip" branch of `play_pause`); green, `play_pause` stops another task's clip
  and reads this one in the same press, as `play_history` does. Verification, on `0bf9b9c5` with
  the cycle not yet committed - the tree committed as `f5c28a7b`: 176 client tests in a full run,
  clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 119): refactor `9b5fad8c` - `play_task` holds Space's
  toggle-or-read (its own clip pauses or resumes, else any other stops and this one is read), for
  a transcript and History; `toggle_clip` folded into it. Verification, on `4e97c8f7` with the
  refactor not yet committed - the tree committed as `9b5fad8c`: 176 client tests in a full run,
  clippy green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 120, red at `9b5fad8c` with the test uncommitted): a task without stored audio.
  `no_audio.rs`: `snapshot.json` with call.ogg's `hasAudio` false; on History interview.mp3 offers
  `Space play` and exactly one row - call.ogg's - says "This task has no audio because it ended
  before chunking." (the browser's note in place of its player); on call.ogg the footer withholds
  `Space play`, and Space reads no clip and sends nothing. Red: no row said it. Green: `Task` reads
  `hasAudio` (serde default false, so an inline fixture without it reads as no audio); a row
  without it carries `history.no-audio` muted, `HISTORY`'s `hint.play` waits for a clip
  (`history_audio`), and `play_history` skips such a row. The transcript (a COMPLETED task) and
  the status page offer no Space without a clip to begin with: the status page has no playback,
  where the browser's shows the player when the task has audio - a parity gap outside this line.
  Verification, on `9b5fad8c` with the cycle not yet committed - the tree committed as
  `a033fe14`: 177 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over `ui-text/en.json`
  clean. Line ticked.
- 2026-11-12 (the user, after cycle 120): "add the discovered item" - the status page's playback,
  the line above, recorded as the parity gap cycle 120 named: the browser's status page shows the
  player when a failed or canceled task has audio, the TUI's has none.
- 2026-11-12 (cycle 121, red at `79ff05a3` with the test uncommitted): no audio device.
  `no_audio_device.rs` runs with `STT_AUDIO_SINK=absent` - a pty test cannot take a sound card
  away, and whether one is there differs from machine to machine (the user's go, the same day).
  Space reads the clip and the bar says "cannot play the audio: no audio device"; `b` still
  returns to History, whose row of that task says it too; Space there, half a second on, still
  says so and no row claims `♪ playing`; `j` still moves. Red: `absent` did not exist, so the run
  opened the sandbox's real output - which has no card - and showed cpal's "The requested audio
  device is not available. It may have been disconnected." and, over the page, six `ALSA lib …`
  errors ALSA printed to the terminal (now in `docs/known-issues.md`, `5103de32`). Green: the
  `absent` sink; cpal's `DeviceNotAvailable` and `HostUnavailable` read `no audio device`, as a
  missing default device did; `ensure_output` opens the output on every play, window and resume
  while none is open - a resume after a failed open had set `playing` with no stream, and a
  device plugged in later is now found - and History's row of the clip shows the failure in the
  problem colour. Verification, on `79ff05a3` with the cycle not yet committed - the tree
  committed as `bffbca6e`: 178 client tests in a full run, clippy and `cargo fmt --check` green,
  `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (cycle 122, red at `2f395b0e` with the test uncommitted): the status page plays.
  `status_playback.rs` (frozen sink): `snapshot.json` and one more task, notes.m4a, CANCELED while
  chunking with no audio. call.ogg's page (FAILED, audio stored) offers `Space play`; Space reads
  `/tasks/4/audio` and the bar on the page's last row reads `0:00 / 10:00` playing, Space again
  pauses it; played again and left with `b`, History shows nothing playing. notes.m4a's page says
  "This task has no audio because it ended before chunking.", offers no Space, and Space reads
  nothing. Red: call.ogg's page offered no `Space play`. Green: Space on a finished task's page
  with `hasAudio` goes to `play_task`; a clip arriving for the open status page plays there;
  `draw_status` gives the clip's bar the page's last row and, without audio, the browser's note;
  `STATUS` offers `hint.play` (`status_audio`); leaving the page stops it - `stop_transcript_playback`
  covers a status page too, as the browser's player goes with its page. `status_page.rs` still
  passes. Verification, on `2f395b0e` with the cycle not yet committed - the tree committed as
  `8e000728`: 179 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 122): refactors `b8df5c75` - `stop_page_playback`, the
  name for what it now stops, a transcript's or a status page's playback - and `f83485e5` - the
  no-audio sentence's key is `audio.none`, beside `audio.failed`, since History's rows and the
  status page both say it. Verification, on `fb0dcf3f` with both not yet committed - the trees
  committed as `b8df5c75` and `f83485e5`: 179 client tests in a full run, clippy green, `gradle
  tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over `ui-text/en.json`
  clean.
- 2026-11-12 (cycle 123, red at `f83485e5` with the test uncommitted): the journey plays.
  `journey.rs`'s upload test starts its TUI on `STT_AUDIO_SINK=null`; after the transcript reads
  back, Space plays jfk's clip from the real service: the bar reads `⏸ 00:00 / <length>` with no
  failure, then `00:02`, then `<length> / <length>` stopped. (The service's short clip shows
  two-digit minutes, `00:11`; the fixtures' `19:45` never did.) Red, at `journey.rs:190`: within
  60 s the bar never showed playing - the final screen stood at `▶ 00:11 / 00:11`. Cause: on the
  audio's arrival the UI hands the clip to the player and draws at once, before the audio thread
  has decoded it, so nothing is playing yet and the loop sleeps until the next message; the
  fixture clips decode quickly enough to win that race, jfk's eleven seconds do not. The same
  loop drew no frame when playback stopped - a clip's end or a failed play - unless another
  message came. Green: the player counts the plays it has not handled (`pending`, raised by
  `play`, `play_window` and `resume`, lowered once the thread handled them), `Player::busy` keeps
  the loop's timer running while a clip plays or a play is pending, and the loop draws once more
  when that stops. Verification, on `f83485e5` with the cycle not yet committed - the tree
  committed as `73d6f36f`: 179 client tests in a full run, clippy and `cargo fmt --check` green,
  `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 123): refactors `aee46e7c` - `Ui::frame` follows the
  playhead and draws, the loop's one frame in its three places - and `1fcf555e` - `animating`
  names the player's state `player_busy`, as `Player::busy` answers it. Verification, on
  `a202542a` with both not yet committed - the tree committed as `1fcf555e` (the rename taken out
  for `aee46e7c`'s commit and put back): 179 client tests in a full run, clippy green, `gradle
  tuiJourney` 3 passed.
- 2026-11-12 (cycle 124, red at `1fcf555e` with the test uncommitted): `Ctrl-R` in the edit
  prompt. `prompt_revert.rs`: with "Good morning." edited to "Good morning!", `e` and a typed `?`
  give the draft "Good morning!?"; `Ctrl-R` sends `POST …/segments/1/revert`, and on its answer
  the prompt stays open holding "Good morning." with no `r` typed; `Ctrl-R` again, the segment no
  longer edited, sends and types nothing; Enter on whisper's text closes the prompt sending
  nothing. Red: no revert arrived - `Ctrl-R` typed an `r`, as every Ctrl-letter in a text prompt
  did. Green: in a text prompt (not the search) `Ctrl-R` is `RevertSegment`; the transcript
  reverts the edit prompt's segment when it was edited, notes it, and when the answer's turns
  arrive puts whisper's text in the still-open prompt; a rename prompt has none to revert; offline
  it is `writes.revert`. `segment_edit.rs`'s `v` and the other edit tests still pass. The browser
  closes its editor after a revert; the line, approved as the user wrote it, keeps the prompt
  open. Verification, on `1fcf555e` with the cycle not yet committed - the tree committed as
  `a0c9d587`: 180 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 124): refactor `52ace5d5` - Enter's unchanged-draft
  check finds the prompt's own segment by `Transcript::segment`, not through the cursor, which
  cannot move while a prompt is open. Verification, on `da987e8e` with the refactor not yet
  committed - the tree committed as `52ace5d5`: 180 client tests in a full run, clippy green,
  `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 125, red at `52ace5d5` with the test uncommitted): the prompt footer.
  `prompt_hints.rs`: an edited segment's edit prompt offers `Enter save`, `Ctrl-R revert`,
  `Esc cancel`, `Backspace erase` and none of the page's keys; closed, the page's come back; an
  unedited segment's prompt withholds `Ctrl-R revert`; the rename prompt has it neither; History's
  search offers `Enter search`, `Esc cancel`, `Backspace erase` and not History's own keys. Red:
  the edit prompt's footer still listed the whole page - `edit`, `Space play`, `back`, `? help`.
  Green: `EDIT_PROMPT`, `RENAME_PROMPT` and `SEARCH_PROMPT` hint lists - the prompt's while one
  takes the typing, before the Speakers panel's and the page's - `hint.prompt-save`,
  `hint.prompt-revert` (waits for `Transcript::edit_reverts`, an edited segment),
  `hint.prompt-erase` and `hint.search-done`; `write`'s key parser reads `Ctrl-X` as the
  lower-case letter with Ctrl held, so the revert hint is left out offline like the other writes.
  `transcript_hints`, `history_search` and `footer_wrap` still pass. Verification, on `52ace5d5`
  with the cycle not yet committed - the tree committed as `8f4cdaa0`: 181 client tests in a full
  run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's shared
  tests 79 passed and prettier over `ui-text/en.json` clean. Line ticked.
- 2026-11-12 (the user's approval after cycle 124): refactor `69414b5b` - the text prompts' footers
  share one tail, `Esc cancel · Backspace erase`, after their own hints; `list` hands back the head
  and the shared tail, so the footer chains them without a joined list being allocated.
  Verification, on `550d33e7` with the refactor not yet committed - the tree committed as
  `69414b5b`: 181 client tests in a full run, clippy green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 126, red at `69414b5b` with the test uncommitted): the download dialog's
  browser, the user's request of 2026-11-11. `download_browser.rs` starts the TUI in a folder
  holding `keep/` (with `a.txt`) and `top.txt`: `d` lists `../`, `keep` and `top.txt` with the
  arrows offered; Enter on `keep` lists `a.txt`; the `../` row and Backspace go back up; typing
  `out.srt` names the target in the shown folder, picks srt, and Enter reads
  `/tasks/3/transcript?format=srt` and writes the body to `out.srt`; Tab still reaches the format,
  where Right steps it without moving in the list. Red: the dialog had no list at all. Green: the
  dialog is the upload's shape - folder line, hints, typed target, format, rows - over the page,
  with `dir`, `entries`, `selected`, `typed` and `error`; it opens in the TUI's folder as the
  upload does; Enter resolves a typed target (a folder is entered, anything else saved) and
  otherwise follows the row (`../` up, a folder in, a file naming the target); Backspace pops or
  goes up; Left goes up and Right opens the folder, the arrows moving the list, and Tab keeps the
  format. `DEVICE`-level changes: `DownloadFocusPath`, `DownloadFocusFormat` and
  `DownloadFormatStep` are gone with the old model, `View::download` holds the dialog itself, and
  the two dialogs share `browse.folder` and the `hint.browse-*` keys. `download.rs`'s
  `up_and_down_move_between_the_path_and_the_format_as_tab_does` pinned the replaced model and
  became `tab_reaches_the_format_where_left_and_right_step_it`; `cursor.rs`'s download step types a
  name now that no path is prefilled; `upload_guidance.rs` reads the renamed keys. Verification, on
  `69414b5b` with the cycle not yet committed - the tree committed as `21497a17`: 182 client tests
  in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the
  frontend's shared tests 79 passed and prettier over `ui-text/en.json` clean. Line ticked.
- 2026-11-12 (the user's approval after cycle 126): refactor `f259bdfa` - the upload and download
  dialogs share one folder `Browser` (`browser.rs`): the folder, its entries, the selection, the
  typed path and the last error, with the keys both use - up, down, typing, Backspace, going up,
  entering, the selected row, resolving a typed path. Each dialog owns one and exposes it; the
  drawing and the footer rules read it. The download dialog still drops its typed name whenever
  another folder is shown, as before; its `error`, never set, is gone. Verification, on
  `0c9ce2a6` with the refactor not yet committed - the tree committed as `f259bdfa`: 182 client
  tests in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (the user, before cycle 127): the elapsed time is tested on a test clock the TUI
  reads, `STT_TUI_CLOCK` (as `STT_AUDIO_SINK=frozen` keeps playback off the real clock), and the
  running-row line is split into its three parts, word for word.
- 2026-11-12 (cycle 127, red at `346e0136` with the test uncommitted): the running row's line.
  `running_row.rs` points `STT_TUI_CLOCK` at a file holding "now" - `lecture.m4a`'s `startedAt`
  plus 12 s - and sends `snapshot.json`: the row reads `Transcribing… · 00:12` beside `42%`; the
  file then holds 13 s, and with no event sent the row reads `Transcribing… · 00:13`. The clock is
  a file, not a value in the variable, so the test can move it while the TUI runs - the only way
  to see it tick without real time. Red: the row read `Transcribing` alone. Green: a running
  row's phase cell is the browser's running line - the stage, an ellipsis and, once started, the
  time since `startedAt` (`running_line`); the view carries `now_ms`, read once per frame from the
  system clock or `STT_TUI_CLOCK`'s file; a running task already redraws on the stenographer's
  timer, so the time ticks; the phase column is 22 wide for the line. The percentage stays in its
  own column. Verification, on `346e0136` with the cycle not yet committed - the tree committed as
  `8ce58f54`: 183 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 127): refactor `80caea8d` - `browser_rows` draws a
  folder browser's scrolled rows (the selection marked, `../` first, folders with `/`, a file
  with the dialog's own label - the upload's "not audio") for both file dialogs. Verification,
  on `e6d13d36` with the refactor not yet committed - the tree committed as `80caea8d`: 183
  client tests in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 128, red at `80caea8d` with the test uncommitted): the running row's progress
  line. `running_progress.rs`: under `lecture.m4a`'s name (transcribing, 42%) a line of `━` and
  `─` whose `━` fills 42% of it from the left and `─` the rest; with the task chunking at 0%, a
  `━` segment two fifths of the line wide instead of an empty bar. Red: no line under the row.
  Green: a running row is two lines tall, its file cell the name and a `seek_line` the file
  column's width - `━` in the accent for the part done, `─` muted for the track, as the player's
  bar draws them; while chunking, `Fill::Sweep` moves a two-fifths segment a column per
  stenographer step, as the browser's indeterminate bar sweeps (a running task already redraws
  on that timer). The percentage stays in its column. Verification, on `80caea8d` with the cycle
  not yet committed - the tree committed as `57478f8f`: 184 client tests in a full run, clippy
  and `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user's approval after cycle 128): refactor `57fb57dd` - the player bar draws its
  line with `seek_line`, as a running row does, instead of its own `━`/`─` spans. Verification, on
  `b5e226b7` with the refactor not yet committed - the tree committed as `57fb57dd`: 184 client
  tests in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-12 (cycle 129, red at `57fb57dd` with the test uncommitted): the diarizing row's speakers
  line. `diarizing_row.rs`: `lecture.m4a` diarizing, half through clustering - its row's third
  line reads `Clustering 50%` after a track three quarters filled (clustering's half, half done,
  after embedding's). Red: no such line. Green: `Task` reads `diarizationStage` and
  `diarizationProgress`; a running row while diarizing adds `speakers_line` - the browser's
  two-stage bar, `(stage + progress) / 2` on a `seek_line`, then the stage and its own
  percentage muted (`stage.embedding`, `stage.clustering`, `stage.progress`); the row is as tall
  as its lines. The "second line" of the wording is the second progress line - the row's third,
  under the transcription line. Verification, on `57fb57dd` with the cycle not yet committed - the
  tree committed as `77e48a91`: 185 client tests in a full run, clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over
  `ui-text/en.json` clean. Line ticked.
- 2026-11-12 (cycle 130, green on arrival): the line's last clause, "cancelling a running or
  started task confirms first". `cancel_confirmation.rs`: `snapshot.json` with lecture.m4a PAUSED
  after it started and memo.wav never started behind it; `c` on the paused one opens "Cancel
  lecture.m4a?" and Escape sends nothing, while `c` on memo.wav cancels task 7 at once with no
  dialog. The rule already stood in `queue_action` (cycle 82's work: a cancel confirms when the
  task is RUNNING, or PAUSED with a `startedAt`), and the suite guarded only its RUNNING and
  never-started halves, so the test is a regression pin - no red. To show it bites, the
  paused-started half was removed for one run: red (no dialog within 20 s), then restored -
  green (Verification below). The line's first clause - a row offering only its status's commands
  - was already pinned by `commands.rs` (retry on a QUEUED task sends nothing) and
  `queue_hints.rs` (the footer's pause, resume and cancel as the status allows). Verification, on
  `2621eaf3` with the test not yet committed - the tree committed as `30918c4e`: 186 client tests
  in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-12 (the user, after cycle 130: "none", and Shift-Home/End for the keys): cycle 131, red
  at `1c2e09fe` with the test uncommitted. `reorder_ends.rs`: a third queued task (third.wav) in
  the snapshot; Shift-End on the first row sends `{"taskIds":[7,9,5]}` and the rows read
  memo.wav, third.wav, lecture.m4a; Shift-Home on lecture.m4a - the cursor is a row, so the test
  steps down twice to reach it - sends `[5,7,9]`; a Shift-End the service refuses with
  `409 {"error":"conflict","message":"the queue changed elsewhere"}` shows that message and the
  order stays. Red: Shift-End sent nothing - the key map knew only Shift-↑↓/J/K. The red also
  caught two test faults: the order helper listed names in a fixed sequence instead of by screen
  row, and the cursor was assumed to follow the task rather than the row. Green: `MoveToTop` and
  `MoveToBottom` (`Shift-Home`, `Shift-End`) in the queue's keys, `move_selected_to` moving the
  id to the end of `task_ids`, and `reorder` shared with the one-step move's PUT; `hint.reorder-ends`
  ("[Shift-Home/End] to the ends") in the queue's footer with the same rule. Nothing reverts
  locally on a refusal because nothing moves locally: the list follows the service's events, so
  the row stays where it was and the notice carries the service's `message`. Verification, on
  `1c2e09fe` with the cycle not yet committed - the tree committed as `2b425d67`: 187 client
  tests in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the
  frontend's shared tests 79 passed and prettier over `ui-text/en.json` clean. Line ticked.
- 2026-11-12 (the user, after cycle 131: "none", and the notices joining the focus): cycle 132, red
  at `e632308f` with the test uncommitted. `queue_notices.rs`: `task-updated` COMPLETED for
  memo.wav and FAILED for call.ogg put "memo.wav transcribed — open" and "call.ogg failed — see
  History" above the upload area, the newest first; Up from the rows reaches the upload area and
  Up again the notices; Enter on the failed one opens History, and on the completed one (Down from
  the newest) its transcript. Red: no notice line. Green: `Notice` (id, filename, status) in
  `Ui`, filled from a finished `task-updated` - newest first, the same task replacing its line, at
  most five (`notices`, `notice_focused`); the queue page's keys run upward through the rows, the
  upload area and the notices, the row keys act only while the rows have them (`on_rows`), and
  Enter opens the notice's target - the transcript of a completed task or History on its row for
  a failed or canceled one; `draw_queue` gives the notices the lines above the upload area
  (`notice.transcribed` / `notice.failed` / `notice.canceled`) and the footer lists the notices'
  keys (`NOTICES`, with `hint.move` when there are two or more); the test also caught two slips of
  its own - the notices are entered at the newest, and the cursor is a row, not a task. Three
  queue tests compared the first mention of a file name, which is now the notice above the rows,
  and so now compare the queue rows (`commands.rs`'s `row_line`, `live.rs`'s and `queue_scope.rs`'s
  `row`). Verification, on `e632308f` with the cycle not yet committed - the tree committed as
  `065b3ff6`: 188 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over `ui-text/en.json`
  clean. Line ticked.
- 2026-11-12 (the user's approval after cycle 132: "focus", and the multi-upload line split into
  four): refactor `f5d99a04` - `QueueFocus { Rows, Upload, Notice(usize) }` says what has the queue
  page's keyboard, where two booleans and an Option did; the view's `upload_focused` and
  `notice_focused` are derived from it, so `draw_queue`, the footer rules and the mouse hit-test
  read what they read before, and a notice the list no longer holds hands the keyboard back.
  Verification, on `f9e6a8c5` with the refactor not yet committed - the tree committed as
  `f5d99a04`: 188 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed.
- 2026-11-12 (the user, after cycle 132: the multi-upload line split into four): cycle 133, red at
  `2414ad31` with the test uncommitted. `upload_batch.rs`: a folder with a.mp3 and b.wav; `u`
  lists `../`, a.mp3, b.wav; Space marks a.mp3, Down and Space mark b.wav, Enter sends them one
  after another - a.mp3's `POST /api/tasks` first, then b.wav's, each answered 201 - and each
  reports "queued <name>" while the dialog closes with the first. Red: no upload arrived - Space
  typed into the path, so Enter resolved a path of spaces and reported a missing file. Green:
  `UploadDialog::marked` and a `Submit::Batch`, entered by Space while nothing is typed and the
  file list has the focus (a typed path keeps its spaces), a tick "✓ " before a marked row, and
  one spawned uploader sending the batch in order with the dialog's options - a file the service
  would refuse by extension skipped with its notice; `hint.mark-file` ("[Space] mark") in the
  dialog's footer while the selected row is a file. Writing the branch first caught a slip: it
  called `submit()` twice, so the second call found the typed path already taken; the match takes
  it once. Verification, on `2414ad31` with the cycle not yet committed - the tree committed as
  `37478368`: 189 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over `ui-text/en.json`
  clean. Line ticked.
- 2026-11-12 (the user's approval after cycle 133: "canmark", and the progress streamed): refactor
  `051ca6d2` - `UploadDialog::can_mark` answers whether Space marks the selected row (nothing
  typed, the list focused, a file on it), which the footer's rule spelled out from the browser's
  state. Verification, on `41294a79` with the refactor not yet committed - the tree committed as
  `051ca6d2`: 189 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed.
- 2026-11-12 (the user, after cycle 133: the progress streamed): cycle 134, red at `e1bb62df` with
  the test uncommitted. `stt-client`'s `upload_progress.rs` reads a `POST /api/tasks` body 8 KiB at
  a time with a pause between pieces and asserts `upload_with_progress` reports a strictly
  increasing count that passes through a value below the length and ends at it; the TUI's
  `upload_batch.rs` holds a.mp3's answer and reads "uploading a.mp3 100%" above the queue, then
  finds the line gone once the file is queued. Red: the client reported nothing, and no line was
  drawn. Green: `upload_with_progress` feeds the request body through a pipe (`tokio::io::duplex`)
  and counts each chunk written, so the count is what has reached the socket and a large recording
  is still never held whole (`upload` delegates with a no-op); the batch's notice loop uses it and
  sends `Message::UploadProgress`/`UploadDone`, `Ui::uploads` keeps a line per file
  (`upload.progress`, "uploading {filename} {percent}%"), drawn above the notices - the queue
  page's areas shift down by that line count. Verification, on `e1bb62df` with the cycle not yet
  committed - the tree committed as `58510551`: 190 client tests in a full run, clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's shared tests 79 passed
  and prettier over `ui-text/en.json` clean. Line ticked.
- 2026-11-13 (the user's approval after cycle 134: "named-areas", "paragraph-lines" and "percent";
  the click line above confirmed, and chosen as the next test): refactor `dfff3578` - `draw_queue`
  destructures its layout into `banner`, `upload_lines`, `notice_lines`, `upload_box` and `queue`,
  as the rest of `view.rs` does, where cycle 134 had renumbered four positional `areas[..]`;
  refactor `8e243e21` - the upload lines and the notice lines render as one `Paragraph` of `Line`s
  into their area each, dropping two hand-built one-row `Rect`s; refactor `16df1ad9` -
  `Uploading::percent` holds the clamped share the view computed inline. Verification, after each
  refactor with it not yet committed: 190 client tests in a full run, clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 134: the click line): cycle 135, red at `f074bdb0`
  with the test uncommitted. `mouse.rs`: a COMPLETED `task-updated` for interview.mp3 puts its
  notice above the upload area, and a marked a.mp3 whose answer is held puts "uploading a.mp3
  100%" above that; a click on memo.wav's line - read from the screen - and `d` must offer
  "Delete memo.wav?", and a click inside the upload area must open the dialog. Red: the dialog
  read "Delete lecture.m4a?" - the click selected nothing. Green: `mouse_target`'s queue arm puts
  the upload area under the banner, the upload lines and the notices, and walks the rows by the
  lines each draws (`queue_row_height`: the name, a running row's progress line, a diarizing
  row's speakers line), where it took every row as one line. The older test in the file clicked
  row 9 - lecture.m4a's progress line since the running row grew it - expecting memo.wav, and
  passed only through that slip; it now clicks memo.wav's line as read from the screen. Writing
  the test first with one file sent by Enter found that a single upload draws no progress line:
  only the marked batch reports progress (`Submit::Choose` calls `upload`). Verification, on
  `f074bdb0` with the cycle not yet committed - the tree committed as `10707c5d`: 191 client tests
  in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-13 (the user's approval after cycle 135: "row-lines", "row-of" and "queue-layout"; the
  single-upload progress line and the scrolled-click line above confirmed, the first chosen as the
  next test): refactor `6ebb3d0d` - `queue_row_lines` builds a queue row's file cell (its name, a
  running row's progress line, a diarizing row's speakers line) for the table, and
  `queue_row_height` counts it for the hit-test, as `history_lines`/`history_height` do;
  refactor `e69c9f91` - `mouse.rs`'s older test finds the upload area's row on the screen rather
  than counting it from the header; refactor `622bb19f` - `queue_layout` gives `draw_queue` and
  `mouse_target` the same five areas, laid out in the page area the last frame drew
  (`view::draw` returns it, `Ui::page` keeps it, `View::page` carries it), where the hit-test
  restated the layout as arithmetic. Verification, after each refactor with it not yet committed:
  191 client tests in a full run (for `e69c9f91`, which touched one test, `mouse.rs`'s two),
  clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 135: the single-upload progress line; during the
  cycle the user reported the `gg` hint and the merge/reassign overlap, both added above): cycle
  136, red at `32ff8d75` with the test uncommitted. `upload_progress.rs`: `u`, Down to a.mp3 and
  Enter send it alone; with its answer held the queue must read "uploading a.mp3 100%", and once
  answered "queued a.mp3" with the line gone. Red: no line - the single path (`Submit::Choose`)
  called `upload`. Green: both submits spawn `send_in_turn`, the batch's uploader moved out of
  `UploadDialog::handle`, with the chosen file as a batch of one; the chosen path keeps its own
  extension check, which leaves the dialog open. Verification, on `32ff8d75` with the cycle not
  yet committed - the tree committed as `01072f5f`: 192 client tests in a full run, clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-13 (the user's choice after cycle 136: no refactor, the same-name upload line added, the
  `g` line next; during the cycle the user reported that a click on a segment should select it,
  added above): cycle 137, red at `d65b1450` with the tests uncommitted. `transcript_hints.rs`
  expects "Home/g start" where it expected "Home/gg start"; `scrolling.rs` presses `g` once.
  Red: the footer read "Home/gg start" (the scrolling test passed: a single `g` always jumped).
  Green: `hint.start` in `ui-text/en.json` reads `[Home/g] start`, and `Action::GotoStart`'s doc
  names `g`. Verification, on `d65b1450` with the cycle not yet committed - the tree committed as
  `99d64d3f`: 192 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed, the frontend's shared tests 79 passed and prettier over
  `ui-text/en.json` clean (both run with `node` on the packages' scripts: the sandbox has no
  `/bin/sh` for `node_modules/.bin`). Line ticked.
- 2026-11-13 (the user's choice after cycle 137: no refactor, the merge/reassign line next):
  cycle 138, red at `ae0af1b8` with the test uncommitted. `merge_reassign.rs` opens a transcript
  whose turns report merges and name Speaker 1 "Alice"; `a` marks a segment, and the footer must
  then offer "Enter move to…" without merge, rename and "Reset merges"; `m`, `r` and `R` are
  pressed; on the Speakers panel (Up twice, to Alice) it must offer "Enter assign" without "Reset
  name" and merge, and `R` and `m` are pressed there; back on the segment `a` unmarks it, and no
  merge line, no rename prompt and no request may have come of the refused keys. Then `m` marks
  Alice for a merge, and the footer must drop mark and "All of the turn"; `a` and `A` are
  pressed and `m` cancels, and no segment may be marked. The first red ran past its first
  footer check only because the event stream had gone silent while it waited, which hides the
  write keys for a reason of its own; the footer waits now also require `● Live`, and the red
  then failed at that first footer check, with the segment selected (the old rules offered merge,
  rename and the reset whatever was marked). Green: `Transcript::handle` refuses `Merge`,
  `ResetMerges` and `r` while segments are marked and `MarkSegments`/`MarkTurn` while a speaker
  is marked; the footer's rules carry the same exclusion (`merges`, `renames`, `has_merges`,
  `renamed`, `marks`). Verification, on
  `ae0af1b8` with the cycle not yet committed - the tree committed as `7c1adbd9`: 193 client
  tests in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line
  ticked.
- 2026-11-13 (the user's approval after cycle 138: "can-methods" and "r-guard"; the segment-click
  line next): refactor `3731223e` - `Transcript::merging`, `can_edit_speakers` and `can_mark` hold
  the exclusion of merging and reassigning once, and the key guards and the footer's rules
  (`merges`, `renames`, `has_merges`, `renamed`, `marks`) read them; the approved `can_merge`
  became `can_edit_speakers`, since the same answer refuses the renames and both resets, not only
  the merge; the footer no longer reads the public `marked` field. Refactor `74603e42` - `r`'s
  refusal is an `if` guard on its own arm, as the other four's are, where it returned early from
  inside the arm (the match ends `handle`, so the two are the same). Verification, after each
  refactor with it not yet committed: 193 client tests in a full run, clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 138: the segment-click line): cycle 139, red at
  `78b75d65` with the test uncommitted. `segment_click.rs` opens `turns.json`'s transcript, finds
  where "joining" and "background" are drawn, and clicks each: the cursor's highlight must read
  "Thanks for joining us today.", then "Let's start with your background.". Red: the highlight
  stayed on "Good morning." - a click on the transcript had no target. Green: `wrap_runs` became
  `wrap_tagged`, carrying each run's segment (counted as the cursor counts them) through the
  wrapping, and `draw_transcript` records, for each line of the turns, the segment each column
  shows, with the turns' area (`Transcript::set_hits`); `mouse_target` reads the segment under a
  click from them, scroll included (`Transcript::segment_at`, `Target::Segment`), and the click
  moves the cursor there as the arrows would, leaving the Speakers panel
  (`Transcript::select_segment`). Writing the green first added a guard that left the cursor
  alone while a prompt or dialog is open - by the code, a click during a rename would send the
  name to the clicked segment's speaker (the submit reads `speaker_index`); no test asked for it,
  so it was taken out and is the discovered line above. Verification, on `78b75d65` with the
  cycle not yet committed - the tree
  committed as `443b8b68`: 194 client tests in a full run (`--no-fail-fast`; the run before it
  failed `commands.rs`'s queue-commands test once, which passed three times alone and in the
  full run - recorded in `docs/known-issues.md`), clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed. Line ticked.
- 2026-11-13 (the user's approval after cycle 139: "tui-click", "turn-rows" and "segment-count";
  the click-during-prompt line confirmed and chosen next): refactor `a707c82e` - one `Tui::click`
  in the pty tests' shared terminal module, where `mouse.rs`, `header_status.rs` and
  `segment_click.rs` each defined it; refactor `ab8e08d9` - `turn_rows` builds a turn's rows
  (name, segments, boxes, time, wrapped and tagged), and `draw_transcript` stacks them, records
  the hits, follows the cursor and pages; refactor `6a127035` - `Transcript::segment_count`, read
  by the cursor's moves, `set_turns` (which now assigns the turns before clamping the cursor to
  them) and the footer's paging rule, where five places summed the turns' segments.
  Verification, after each refactor with it not yet committed: for `a707c82e`, a test-only change,
  the three changed files' tests and clippy over every target; for the others 194 client tests in
  a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3
  passed.
- 2026-11-13 (the user's choice after cycle 139: the click-during-prompt line): cycle 140, red at
  `f075ca91` with the test uncommitted. `segment_click_prompt.rs`: `r` on the first segment opens
  "Rename: Speaker 1"; a click on Speaker 2's "Happy to be here." while it is open, then the name
  replaced with "Alice" and Enter, must send `PUT /api/tasks/3/speakers/0`. Red: the rename went
  to `PUT /api/tasks/3/speakers/1` - the click had moved the cursor to Speaker 2's segment, and
  the submit reads the cursor's speaker. Green: the segment click moves the cursor only while the
  input mode is `Normal` - no confirmation, dialog, text prompt or "Move to" list. Verification,
  on `f075ca91` with the cycle not yet committed - the tree committed as `d049ed53`: 195 client
  tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed. Line ticked.
- 2026-11-13 (the user's approval after cycle 140: "position-of"; the restore-defaults line next,
  reached - the user's choice - by a last row under the seven fields, as the browser's button
  sits under its fields, whose Enter opens the shared confirmation): refactor `67f898d6` -
  `terminal::position_of` finds where a text is drawn, shared by `segment_click.rs` and
  `segment_click_prompt.rs`, the second of which spelled the search out inline. Verification,
  with it not yet committed: the two files' tests and clippy over every target (a test-only
  change).
- 2026-11-13 (the user's choice after cycle 140: the restore-defaults line, reached by a last
  row): cycle 141, red at `6927e1dc` with the test uncommitted. `settings_restore.rs` opens the
  settings with every field set, presses Down seven times past the fields, and expects the footer
  to offer "Enter restore defaults" and not "Enter choose / edit"; Enter must open "Restore
  default settings?" with the browser's body, Escape must close it with nothing sent; then Up off
  the row, a click back on it, Enter and Enter must send `DELETE /api/settings` with the secret,
  and the answer - `docs/api/settings.json`, the contract's defaults - must show in Threads
  (auto), Language (auto), Speakers (auto) and Max speakers (8). The pty harness now hands a
  `DELETE /api/settings` to the test as a command. Red: the footer had no restore hint - Down
  stopped at the last field. Green: `stt_client::restore_settings` sends the `DELETE` and reads
  the settings it answers; `SettingsScreen` has a row past `FIELDS` (`on_restore`), drawn with
  `settings.restore` in the value column and described by the dialog's body; its Enter opens
  `Confirm::RestoreDefaults` - the queue's dialog, with `settings.restore-title`,
  `settings.restore-body` and `settings.restore-confirm` - whose Enter sends the restore, the
  answer arriving as `SettingsSaved` (the stored settings, so the CPU-fallback notice clears with
  them, and the screen's fields) and a refusal as `settings.restore-failed`; the footer shows
  `hint.restore-defaults` on the row and `hint.choose` elsewhere; the write gate names the row's
  Enter and the confirmation's "restore defaults"; the settings hit-test reaches the row. The
  full run then failed `settings_terminal.rs`, which reached the last field with seven Downs
  that relied on Down stopping there; it presses six now. Verification, on `6927e1dc` with the
  cycle not yet committed - the tree committed as `a2dd4a73`: 196 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the
  frontend's shared tests 79 passed and prettier over `ui-text/en.json` clean. Both lines
  ticked.
- 2026-11-13 (the user's approval after cycle 141: "restore-beside-save", "field-accessor" and
  "dialog-arms"; the validation line next): refactor `812e5a8e` - `settings::restore` sends the
  restore beside `settings::save`, and `Ui::answer` calls it; refactor `6f784425` -
  `SettingsScreen::field` names the selected row's setting (`None` on the restore row), read by
  the screen and its drawing where eight lookups indexed `FIELDS` by the row; refactor
  `a6e9615a` - each arm of `Ui::dialog` builds its dialog through one closure, where the task
  confirmations went through a tuple and the restore returned early. Verification, after each
  refactor with it not yet committed: 196 client tests in a full run (`--no-fail-fast`), clippy
  and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 141: the validation line): cycle 142, red at
  `57233952` with the test uncommitted. `settings_validation.rs` opens the settings with every
  field set; Max speakers' draft "0" and Enter must show "Max speakers must be a whole number
  from 1 to 20." in the Max speakers row with the draft still open and nothing sent, and Escape
  must leave 12 with the message gone; then the device dropdown's next option (`gpu:0`) is
  chosen, the `PUT /api/settings` answered 400 with `docs/api/error.invalid-device.json`, and
  the Device row must keep `auto` with the service's message while Threads (4) and Max speakers
  (12) stay. Red: the validation message stood on the bottom notice line, not in the row.
  Placement (the agent's call, stated here): the message follows the row's value - after the
  draft and its keys while one is open - in the danger colour, as the browser puts each field's
  message under that field. Green: `SettingsScreen` holds one message for one field
  (`message`, `refused`); an out-of-range number sets it, Escape and a successful save clear
  it; `settings::save` takes the field it changes and sends a refusal as
  `Message::SettingRefused(field, message)`, which the settings page puts in that row (the
  notice elsewhere), the theme key's save passing no field and keeping the notice; the TUI saves
  one setting per write and changes nothing before the answer, so the field it changed is the
  one refused and the row already shows the stored value (the browser maps the error's code to
  its field; the client's error keeps only the message). `value_cell` draws the message, with
  the draft's line now built by `draft_line`. Verification, on `57233952` with the cycle not yet
  committed - the tree committed as `284234f3`: 197 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line
  ticked.
- 2026-11-13 (the user's approval after cycle 142: "refusal-one-path"; the live-message line
  confirmed; the settings line 583 next): refactor `8123233b` - `settings::save` sends every
  refusal as `SettingRefused(Option<Field>, message)` and the Ui places it, in the setting's row
  on the settings page or as the "settings not saved" notice, where `save` formatted the theme
  key's notice itself. Verification, with it not yet committed: 197 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's answers before cycle 143: split the settings line into five, the
  browser's device labels, the load failure shown on the page; the agent took the five lines in
  their order, the introduction first): cycle 143, red at `25a3a5a0` with the test uncommitted.
  `settings_intro.rs` opens the settings at 160 columns and expects the browser's introduction
  whole, above the Device row. Red: no introduction. Green: `settings.intro` in
  `ui-text/en.json`, drawn wrapped above the settings list; `settings_layout` gives the drawing
  and the settings hit-test the same three areas - the introduction as tall as it wraps, the
  list, the description - where the hit-test counted the rows from the page's top. `mouse.rs`
  clicked the Threads row at a fixed row, which the introduction moved down; it clicks where the
  row is drawn now. Verification, on `25a3a5a0` with the cycle not yet committed - the tree
  committed as `51b40833`: 198 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's shared tests 79
  passed and prettier over `ui-text/en.json` clean. Line ticked.
- 2026-11-13 (the user's approval after cycle 143: "history-row-of" and "intro-once"; the
  settings-scroll line confirmed; the "Saving…" line next): refactor `51a11bd2` - `mouse.rs`
  double-clicks the History row where `interview.mp3` is drawn, and the `BELOW` offset goes;
  refactor `c47630a6` - `settings_layout` measures the introduction its caller read, where it
  read the catalog text a second time each frame. Verification, after each refactor with it not
  yet committed: for `51a11bd2`, a test-only change, `mouse.rs`'s tests and clippy over every
  target; for `c47630a6` 198 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 143: the "Saving…" line): cycle 144, red at
  `19be1d4d` with the test uncommitted. `settings_saving.rs` opens the settings - no "Saving…" -
  raises Threads from 4 to 5 and holds the `PUT /api/settings`: the page must say "Saving…";
  answered, Threads must read 5 and "Saving…" be gone. Red: nothing said it was saving.
  Placement (the agent's call): the settings list's title reads "Settings · Saving…", where the
  browser has a status line under its introduction - a line there would move every row down
  for the length of a save, under the cursor and the mouse. Green: `SettingsScreen::store`
  marks the page as saving and calls `settings::save`, for each of the page's four saves;
  `saved` and `refused` clear it; `settings.saving` in `ui-text/en.json`. The theme key's save
  and the restore do not go through it; the browser's reset does show "Saving…" (its
  `settingsMachine` queues the `DELETE` in the same `saving` state) - a discovered line, put to
  the user at the checkpoint. Verification, on `19be1d4d` with the cycle not yet committed - the
  tree committed as `d060f4fc`: 199 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's shared tests 79 passed
  and prettier over `ui-text/en.json` clean. Line ticked.
- 2026-11-13 (the user's answers after cycle 144: no refactor, the restore-saving line confirmed,
  the load-failure line next).
- 2026-11-13 (the user's choice after cycle 144: the load-failure line): cycle 145, red at
  `9241fa1d` with the test uncommitted. `settings_failure.rs` opens the settings with the read
  answered 500 (`{"error":"internal","message":"the database is locked"}`): the page must show
  "cannot load the settings: the database is locked" with the Settings tab open and "Enter
  retries"; Enter must read `GET /api/settings` again, and the answered read must show the
  settings (Threads 4) with the error gone. Red: the failed read was a notice and the page
  stayed on the queue. Green: `Screen::SettingsFailed(String)` - the settings page with why the
  read failed, drawn with the introduction, the error in the danger colour and "Enter retries";
  the footer offers `[Enter] retry` (`hint.read-again`); Enter reads the settings and the
  frontends again; `open_settings` reports a failed read as `Message::SettingsFailed` instead of
  the notice. Verification, on `9241fa1d` with the cycle not yet committed - the tree committed
  as `b123c8b2`: 200 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed, the frontend's 315 tests passed and prettier clean. Line ticked.
- 2026-11-13 (the user's approval after cycle 145: "one-settings-list" and "intro-helper"; the
  device-labels line next): refactor `a208362e` - the settings page's keys are one `SETTINGS`
  list, its field keys gated on the page with fields, its restore key on the restore row and its
  retry key on the page a failed read left, where `SETTINGS_FAILED` repeated most of it;
  refactor `ba1af1ac` - `settings_intro` holds the introduction's text, read by the settings
  page, its hit-test and the failed page. Verification, after each refactor with it not yet
  committed: 200 client tests in a full run, clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 145: the device-labels line): cycle 146, red at
  `28753f51` with the test uncommitted. `settings_devices.rs` opens the settings with the
  fixture's devices and GPU names: the Device row must read "Auto"; the dropdown must list
  "CPU", "GPU 0 — NVIDIA GeForce RTX 3060" and "GPU 1 — AMD Radeon RX 6600" with "✓ Auto"; one
  Down and Enter must store `"device":"gpu:0"`, and the row must then read "GPU 0 — NVIDIA
  GeForce RTX 3060". Red: the row read "auto". Green: `device_label` names devices as the
  browser's `deviceLabel` does - "Auto", "CPU", "GPU <index> — <name>" (the index as the
  service gives it), or the value as it is; the tests that read the old labels (`settings.rs`,
  `settings_dropdown.rs`, `settings_validation.rs`, `quit.rs`) read the new ones. Verification,
  on `28753f51` with the cycle not yet committed - the tree committed as `5733dc5d`: 201 client
  tests in a full run, clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the
  frontend's 315 tests passed and prettier clean. Line ticked.
- 2026-11-13 (the user's approval after cycle 146: "device-fixture" and "label-flat"; the
  failed-probe line next): refactor `1ff75e41` - `settings_fixture::GPU0` and `GPU1` hold the
  labels the fixture's `gpuNames` give, read by `settings.rs`, `settings_dropdown.rs` and
  `settings_devices.rs`; refactor `1ff8d18d` - `device_label` reads as a flat sequence (auto,
  cpu, a gpu index, then the name when the probe reported one). Verification, after each
  refactor with it not yet committed: 201 client tests in a full run, clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 146: the failed-probe line): cycle 147, red at
  `4fb0e4b9` with the test uncommitted. `settings_probe.rs` opens the settings with
  `docs/api/settings.probe-failed.json` (cpu and auto only, `deviceProbeError` "Executable not
  found: whisper-cli"): the page must show "The device probe failed — Executable not found:
  whisper-cli"; the Device row must read "Auto", and the dropdown must offer CPU and Auto and
  no GPU. Red: no probe line. Green: `Settings` reads `deviceProbeError` (read-only, kept out
  of the PUT body); `settings_probe` names it as the browser's callout does
  (`settings.probe-failed`), drawn under the introduction in the danger colour, and
  `settings_layout` gives it its rows, so the list and the hit-test stay where the frame drew
  them. The test's first green failed on its own assertion - the Device row's description
  mentions a GPU - so it checks the dropdown's options instead. Verification, on `4fb0e4b9`
  with the cycle not yet committed - the tree committed as `365f10e7`: 202 client tests in a
  full run (`--no-fail-fast`; the run before it failed one test once, which passed in the next
  full run - the flake recorded in `docs/known-issues.md`), clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed, the frontend's 315 tests passed and prettier clean. Line
  ticked.
- 2026-11-13 (the user's approval after cycle 147: "settings-header"; the restore-saving line next):
  refactor `94a8c544` - `settings_header` names the settings page's header, the introduction and
  the device probe's failure, read by `draw_settings` and the settings hit-test. Verification,
  with it not yet committed: 202 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 147: the restore-saving line): cycle 148, red at
  `c487c8ba` with the test uncommitted. `settings_restore.rs`'s second test opens the settings,
  Downs past the seven fields to the restore row, opens and confirms the dialog, and holds the
  `DELETE /api/settings`: the page must say "Saving…"; answered with the defaults, Threads must
  read "auto" and "Saving…" be gone. Red: the restore said nothing. Green:
  `SettingsScreen::restore` marks the page as saving and sends the restore; the confirmation's
  Enter calls it; the restore's refusal is `Message::RestoreFailed`, which clears the page's
  saving and shows the "defaults not restored" notice, where it sent the notice directly.
  Verification, on `c487c8ba` with the cycle not yet committed - the tree committed as
  `06b04813`: 203 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed, the frontend's 315 tests passed and prettier clean. Line
  ticked.
- 2026-11-13 (the user's approval after cycle 148: "saving-pair"; the live-message line next):
  refactor `984ef46b` - `start_saving` and `stop_saving` hold the saving flag the four save paths
  set and the three answers cleared. Verification, with it not yet committed: 203 client tests in
  a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3
  passed.
- 2026-11-13 (the user's choice after cycle 148: the live-message line): cycle 149, red at
  `48078b31` with the test uncommitted. `settings_validation.rs`'s second test opens the
  settings, sets Max speakers to "0" (its message stands), clears the draft (empty, the message
  stays) and types "5": a valid draft must say nothing before Enter, and Enter must save
  `"maxSpeakers":5`. Red: the message stayed on the valid draft. Green: the message knows its
  origin (`Standing { field, text, refused }`) - a draft's validation or the service's refusal -
  and `handle_number` clears a draft's message as soon as `draft_valid` accepts the draft (an
  empty or whole thread count, a count from 1 to `MAX_SPEAKERS` for the speakers rows), while a
  service's refusal stands until the setting is saved again. Verification, on `48078b31` with
  the cycle not yet committed - the tree committed as `85e95aaa`: 204 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the
  frontend's 315 tests passed and prettier clean. Line ticked.
- 2026-11-13 (the user's choice after cycle 149: the language-filter line; before the cycle the
  user settled that `q` types in a dropdown, that only the language list filters, and that a label
  matches when it holds the typed text): cycle 150, red at `5fce4be8` with the test uncommitted
  (the test run against the source stashed). `settings_language_filter.rs` opens the language
  dropdown, types "j" - a letter now, not a move - and expects only "ja  japanese" with
  "filter: j"; Backspace widens it to "en  english" again; "fr" narrows to "fr  french" and Enter
  stores `"defaultLanguage":"fr"`. Green: `Mode::Choose` on a page maps the arrows, Enter, Escape,
  Backspace and characters (the transcript's Move to list keeps j/k/q); `Dropdown` holds the typed
  filter and `shown()` narrows the options by the label, case-insensitively; the cursor moves,
  Enter chooses and the draw reads the shown options; `handle_dropdown` appends and pops the
  filter; `settings.dropdown-filter` names it in the keys line. `quit.rs`'s dropdown test becomes
  `q_is_a_character_in_a_settings_dropdown_rather_than_escape`, and `settings_dropdown.rs` moves
  the device list with Up. Verification, on `5fce4be8` with the cycle not yet committed - the tree
  committed as `72908e26`: 205 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's 315 tests passed and
  prettier clean. Line ticked.
- 2026-11-13 (the user's approval after cycle 150: "shown-once"; the hint line next, confirmed):
  refactor `1fdae6bc` - `Dropdown` keeps the shown options' indexes, rebuilt when the filter
  changes, where `shown()` filtered and allocated per call - twice on Enter and once a frame; the
  drawing reads the indexes into the options. Verification, with it not yet committed: 205 client
  tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed.
- 2026-11-13 (the user's choice after cycle 150: the hint line, confirmed): cycle 151, red at
  `c9280c70` with the test uncommitted. `settings_language_filter.rs`'s first open of the language
  dropdown must read "type filter" in its keys line before anything is typed. Red: the line said
  only "Enter choose · Esc close". Green: `settings.dropdown-filter-hint` names typing, drawn by
  the filtering dropdown; the others keep `settings.dropdown-hint`, and `settings_dropdown.rs`
  reads the language dropdown's new line. Verification, on `c9280c70` with the cycle not yet
  committed - the tree committed as `330c2361`: 205 client tests in a full run (`--no-fail-fast`),
  clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's 315 tests
  passed and prettier clean. Line ticked.
- 2026-11-13 (the user's approval after cycle 151: "hint-method"; the settings-scroll line next):
  refactor `3876d586` - `Dropdown::hint_key` holds the keys line's catalog key, where
  `draw_dropdown` picked it with an `if`. Verification, with it not yet committed: 205 client
  tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed.
- 2026-11-14 (the user's choice after cycle 151: the settings-scroll line): cycle 152, red at
  `c487c8ba` with the test uncommitted. `settings_scroll.rs` opens the settings at 120×14, Downs
  past the fields to the restore row so the list scrolls, and clicks the Terminal command row
  where it is drawn: Enter must edit the command in its row ("Enter save · Esc cancel · empty:
  …"). Red: the click selected a row counted from the list's top, not the drawn one (the
  description read "Language"). Green: `draw_settings` records the first row the table shows
  (`SettingsScreen::set_offset`), and the hit-test adds it to the clicked row
  (`SettingsScreen::offset`). Verification, on `c487c8ba` with the cycle not yet committed - the
  tree committed as `296f6abd`: 206 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-14 (the user's choice after cycle 152: the queue-scroll line): cycle 153, red at
  `ffaa7af0` with the test uncommitted. `queue_scroll.rs` opens the queue at 120×13, Downs to
  memo.wav so the table scrolls it under the running row, and clicks memo's line: `d` must offer
  memo, not lecture. Red: the dialog read "Delete lecture.m4a?" - the click mapped the line as if
  the table started at its first row. Green: `draw_queue` records the first row the table shows
  (`View::queue_offset`), and the hit-test walks the rows from it, counting the lines each takes
  (`queue_row_height`) - the clicked line is counted from the first shown row, not added to its
  index. Verification, on `ffaa7af0` with the cycle not yet committed - the tree committed as
  `6a014ae2`: 207 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-14 (the user's approval after cycle 153: "line-to-row" - the form could not carry it, so
  the user said so; the footer-click line next, chosen): refactor `64f3ee8d` -
  `row_at` walks a table's rows from the first it showed, over the lines each takes, and names the
  row a clicked line falls in; the queue's hit-test reads it over its row heights, the settings'
  over its one-line rows, where the settings' counted `offset + line`. Verification, with it not
  yet committed: 207 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed.
- 2026-11-14 (the user's choice after cycle 153: the footer-click line): cycle 154, red at
  `a64fb0d7` with the test uncommitted. `footer_click.rs` opens the queue at 160 columns: a click
  on the footer's "move" hint (several keys) must send nothing, and a click on "pause" (one key)
  must send `POST /api/tasks/5/pause`. Red: the pause click sent nothing. Green: `draw` records
  each footer hint that names one key (`writes::hint_key`) with the row and columns the footer
  packed it into (`FooterHint`), the `View` and `Ui` carry them, `mouse_target` returns the press
  and `Ui::click` feeds it through the key path - the write gates and the open dialog included;
  a multi-key hint is no target. Verification, on `a64fb0d7` with the cycle not yet committed -
  the tree committed as `1a188f51`: 208 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's 315 tests passed and
  prettier clean. Line ticked.
- 2026-11-14 (the user's approval after cycle 154: "pack-once"; the horizontal-scroll line next):
  refactor `b07565f4` - `hints::pack` returns the packed rows and each hint's row with the columns
  it covers, and `footer_click_hits` reads that, where it restated the separator and the wrap
  rule. Verification, with it not yet committed: 208 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-14 (the user's choice after cycle 154: the horizontal-scroll line): cycle 155, red at
  `c346b4b9` with the test uncommitted. `horizontal_wheel.rs` scrolls right on the queue and
  expects History's tab, left and expects the queue's; on a transcript a left scroll must take
  the queue's tab and leave the transcript - the segment cursor stays with the page it left.
  Red: horizontal scrolls did nothing. Green: the mouse match sends `ScrollLeft`/`ScrollRight` to
  `open_page` with the tab before/after the page's - the paths Left and Right take on a page -
  whatever the screen, a transcript included, so its cursor is untouched. Verification, on
  `c346b4b9` with the cycle not yet committed - the tree committed as `1c0e933d`: 209 client
  tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed. Line ticked.
- 2026-11-14 (the user's choice after cycle 155: the page-step refactor): refactor `32e16923` -
  `step_page(forward)` opens the tab after the page's or the one before it, and
  `PageNext`/`PagePrev` and `ScrollRight`/`ScrollLeft` both go through it, so the step is spelled
  once. Verification, with it not yet committed: 209 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-14 (the user's choice after the page-step refactor: the same-name uploads line): cycle
  156, red at `efedf66f` with the test uncommitted. `same_name_uploads.rs` sends a.mp3 alone and
  holds its answer, then sends the same file again while the first is unanswered, and expects two
  `uploading a.mp3` lines; each answer must take only its own. Red at the two-line wait: one line,
  the second upload's progress having replaced the first's. Green: each file of a batch takes an
  id from a process-wide counter (`NEXT_UPLOAD`), `UploadProgress`/`UploadDone` carry it and
  `Ui::uploads` finds a line by it. Verification, on `efedf66f` with the cycle not yet committed -
  the tree committed as `02710155`: 210 client tests in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line ticked.
- 2026-11-14 (the user's choice after cycle 156: the count helper): refactor `98ce70dc` -
  `uploading_lines(screen, name)` in `same_name_uploads.rs` replaces the line filter written three
  times. Verification, with it not yet committed: 210 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-14 (the user's choice after the helper refactor: the speakers-off line): cycle 157, red at
  `f16daa2a` with the test uncommitted. `speakers_off.rs` opens interview.mp3 with a turns answer
  whose speakers list is empty and every `speakerId` null. Red at the very first wait: "cannot
  load the transcript: invalid type: null, expected i64" - `Turn.speaker_id` was not optional,
  so the TUI could not read a transcript from a task transcribed with speakers off at all, while
  the service and the browser send null. Green: `Turn.speaker_id` is `Option<i64>` (the speaker
  lookups compare `Some(id)`), the speakers panel takes no rows when the list is empty (the
  browser draws no panel either), and `Transcript::can_mark` needs a speaker, so the footer's
  mark keys and the reassignment are gone. The test also moves the cursor and edits a segment
  through the PUT, so reading and editing are pinned. Verification, on `f16daa2a` with the cycle
  not yet committed - the tree committed as `acabb0e8`: 211 client tests in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed. Line
  ticked.
- 2026-11-14 (the user's choice after cycle 157: the can-edit refactor): refactor `dff96fc9` -
  `Transcript::can_edit_speakers` asks for a speaker as well as no marked speaker, so the footer's
  rename rule relies on it alone; the merge rule keeps its two-speaker check. Verification, with
  the refactor alone (cycle 158 uncommitted beside it): full client runs of 211 tests, clippy and
  `cargo fmt --check` green; the journey ran after cycle 158's changes, below.
- 2026-11-14 (the user's choice after cycle 157: the playback-return line; the key `f` chosen
  2026-11-14): cycle 158, red at `9a1b2881` with the test uncommitted. `follow_playhead.rs` plays
  the frozen clip, `]` seeks and the cursor walks, Down moves the cursor off the playhead, and the
  footer must offer "follow playback"; `f` then returns the cursor and "Following playback" comes
  back; Enter after `f` seeks to 0:02 (the playing segment's 2.4 s), not 0:05, which proves where
  the cursor sits. Red: the first wait found no "Following playback" anywhere. Green: the
  catalogue's `hint.follow` / `hint.following`, `Action::FollowPlayhead` on `f` (a transcript's
  key, and the queue's exhaustive match ignoring it), the footer's `following` / `follow_offer`
  rules (both only while a clip plays), and the action's `track` + `start_tracking`. The test
  first compared the catalogue's bracketed text; the footer draws the key without its brackets, so
  it compares "follow playback". Verification, on `9a1b2881` with the cycle not yet committed -
  the tree committed as `661a1bcc`: 212 client tests in full runs (see the finding below), clippy
  and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, the frontend's 315 tests passed and
  prettier over `ui-text/en.json` clean. Line ticked.
- Finding (2026-11-14, cycle 158's full client runs, reported to the user at the checkpoint):
  `commands.rs:170` failed in two of six recent full runs - the second reorder's body
  `{"taskIds":[5,7]}` where `[7,5]` was expected - while the other four runs were green. The wait
  before those keys compares `text.find` positions, which a notice line above the rows can
  satisfy, so the rows may not have taken the new order when the keys arrive. No fix made without
  the user's word.
- 2026-11-14 (the user's call after cycle 158: fix the flake): `aed0831c` - `commands.rs`'s
  reorder wait now requires both rows and their order (`row_line`), not `text.find` positions a
  notice line could satisfy. Verification, with it not yet committed: five single runs of
  `commands.rs` and one full client run of 212 tests green, `cargo fmt --check` green.
- 2026-11-14 (the user's choice after cycle 158: the follow refactor): refactor `fe9f3e95` -
  `Transcript::follow_playhead(at)` tracks the cursor and starts it walking again; `main.rs` stops
  setting the transcript's tracking itself. Verification, with it not yet committed: 212 client
  tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle
  tuiJourney` 3 passed.
- 2026-11-14 (the user's choice after cycle 158: the duplicate-upload line): cycle 159, red at
  `ec08a35c` with the test uncommitted. `duplicate_upload.rs` uploads a.mp3, answers 409 with
  `error.duplicate-audio.json` (task 3, interview.mp3), expects "a.mp3 already uploaded — open",
  then Enter on that notice opens task 3's transcript (GET …/3/turns). Red: no such line.
  Green: `stt-client`'s `UploadError { message, duplicate }` (`ApiError` reads `taskId`;
  `upload_with_progress` returns it, the file and answer errors converting in), the batch sending
  `Message::Duplicate`, the notice line (`notice.duplicate`), and `open_duplicate` routing Enter
  by the existing task's status as the browser's `duplicateTarget` does - transcript once done,
  queue row while pending, History on a failed or canceled one. The full run caught the
  refused-upload test's duplicate half still expecting the service message; it now expects the
  offer (the 413 half keeps the message case). Verification, on `ec08a35c` with the cycle not yet
  committed - the tree committed as `3209d471`: 213 client tests in a full run (`--no-fail-fast`),
  clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed, prettier over
  `ui-text/en.json` clean. Line ticked.
- Finding (2026-11-14, cycle 159's full runs): `status_playback.rs:81` ("leaving the page stops
  its playback") failed once in a full parallel run, 0.53 s in, and passed five of five single
  runs; the notice above it (`commands.rs:170`) was the same shape. Both are read-back races
  under load, not behavior changes. Reported to the user at the checkpoint.
- 2026-11-14 (reviewing cycle 159's own code, before the checkpoint): `3209d471`'s pending branch
  indexed the snapshot's task list, but the queue's rows are `state::rows` in the queue's order;
  corrected in `6bab782c`. The test covers the finished duplicate; the pending one's row is
  pinned only by this reading. Verification of the fix: 213 client tests in a full run,
  clippy and `cargo fmt --check` green.
- 2026-11-14 (the user's choice after cycle 159: the notice refactor): refactor `72585a83` -
  `Ui::offer_notice` removes the task's old line, inserts the new one at the
  top, caps the list at five and hands the keyboard back when a focused line is gone; a finished
  task's line and a duplicate upload's offer both call it. Verification, with it not yet
  committed: 213 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed.
- 2026-11-14 (the user's choice after the notice refactor: the lost-connection line): cycle 160, red
  at `172db368` with the test uncommitted. `upload_lost.rs` sends a.mp3, reads the request whole,
  cuts the connection before an answer (`Upload::cut_off`), drops the event stream, and expects
  "a.mp3 waiting for the connection…"; after the TUI reconnects and a snapshot arrives, the file is
  sent again and its answer queues it. Red at the waiting line. Green: `UploadError::lost` marks a
  request or answer that could not travel (a refusal is not lost), the batch hands the current
  file and the ones behind it to `Message::UploadHeld`, the Ui keeps them with the waiting line
  (`upload.waiting`, the upload lines' first) and `send_in_turn` runs again in the snapshot
  handler that clears `disconnected`. The layout slip (six areas declared over five constraints)
  the first build made panicked the TUI inside ratatui's `areas`, which the test caught and this
  entry records. Verification, on `172db368` with the cycle not yet committed - the tree committed
  as `544917da`: 214 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed, prettier over `ui-text/en.json` clean. Line ticked.
- 2026-11-14 (handover, the user: "Let me pull your code and test it myself"): the third
  amendment's designs line is the only one open - 152 of 153 ticked. The screens were dumped at
  84x30 for a text review (a throwaway test, removed here, which the full suite's 214 tests then
  ran without). The user reviews the branch directly: `devenv up`, then `cargo run -p stt-tui`
  from `client/` (the TUI reads `config.json` from `$XDG_CONFIG_HOME/stt-app`, default
  `~/.config/stt-app`, and `runtime.json` from `$XDG_RUNTIME_DIR/stt-app`, default
  `~/.cache/stt-app`). The line is not ticked and not signed off.
- 2026-11-14 (the user: "You have design specs. Please follow them", after reviewing the screens;
  order agreed: upload, settings, transcript, queue, history): designs pass, the upload dialog.
  `docs/design/tui/upload-dark.svg` is now what the dialog draws: nearly the page's size, the
  accent frame with the accentText title, the folder and `browse.mark-hint`, a rule, entries as
  `[✓]`/`[ ]` + name + size + date (size from metadata, date `%b %-d`), the selected row filled in
  the accent tint, a rule, `Language  de ▾   Speakers  3 ▾` chips on one row, a rule,
  `Or type a path`, and the dialog's own line `Upload 0 files  Tab options  / type a path  Esc
  cancel`. The speakers choice is one control (the user: "There's no point in setting 'Max
  speakers' if Speakers are set to 'auto'"): a fixed count is sent as `maxSpeakers`; auto and off
  send the stored maximum. `Field` drops `Count`/`Max`; the palette gains the shared `text` and
  `surfaceRaised` tokens. Red was the user's review of the old dialog; the contract tests moved to
  the design's strings (`upload_options.rs`, `upload_edge.rs`, `upload_guidance.rs`, `cursor.rs`).
  Verification: 214 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green, `gradle tuiJourney` 3 passed, prettier over `ui-text/en.json` clean. The designs line
  stays open: settings, transcript, queue and history follow, each its own commit and checkpoint.
- 2026-11-14 (design pass, the user's corrections after reviewing the screens): the upload dialog's
  rows, the tabs, the header and the transcript follow their signed-off designs, and the settings'
  speakers row is one choice. Upload: a non-audio file is greyed out whole - `notes.txt (not
  audio)` with one pair of parentheses, no mark, no size and no date - and the keyboard steps over
  it, so it is never marked or picked from the list (`Browser::selectable`; a typed path still says
  why it is refused), replacing the "marked and refused" behaviour and its test; `↰ ../` sits on
  the folder rows' column; the selected row's accent tint fills the whole line. Tabs: no digits or
  brackets, the open page in an accent chip with the page's own background for its text and no key
  hint, the others muted with a live shortcut's first letter in the text colour and underlined.
  Header: `● Live ◐ theme: …` is pushed to the row's right end. Transcript: a turn reads
  `00:00  Speaker 1` on its first row - the time muted, the name in its speaker's tint - with the
  text in the page's text colour on the rows below, aligned seven columns in (`wrap_tagged` takes a
  first-row start beside its lead; the old "time under the name" layout is gone). Footer: the hints
  are separated by three spaces with their words muted, as the design draws them. Settings: the
  Speakers row is one choice - auto, off or a count, a fixed count being `maxSpeakers` too, as the
  user asked ("There's no point in setting 'Max speakers' if Speakers are set to 'auto'") - which
  retires the `Max speakers` row, the typed count and the draft-validation message; a number row
  takes digits only, so a draft is never invalid before Enter, and the service's refusal still
  reverts one row with its message (the validation test-list line is amended above, its "draft
  message clears" line removed). Tests follow: `turn()` in `tests/terminal/mod.rs` reads a turn's
  two rows, tab waits name the page's own content (the bar names every page always), the tab
  marker compares with the text colour, and the settings tests lose the deleted row. Verification:
  216 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check` green, and
  `gradle tuiJourney` 3 passed. The designs line stays open: the queue and history screens remain.
- 2026-11-14 (design pass, the user's reports while trying the screens, and a self-check against
  the SVGs at 100x30): every tab keeps the chip's padding, so switching pages no longer moves the
  titles; the upload dialog's chips open floating lists with Enter or a click (the language
  catalogue; auto, off and 1..=20 for the speakers) where Up/Down move, Enter chooses and Escape
  closes the list alone, and a typed path still submits with Enter; a click in the dialog selects a
  file row, a double click enters a folder even while files are marked (a file submits), and a
  click outside any open dialog - Help, a confirmation, the "Move to" list, upload, download -
  closes it; a double click plays a transcript segment and opens a Settings row; the key reference
  lists one hint per row; the frame's base style is the theme's own text colour, so no cell falls
  back to the terminal's default foreground; and a ticked file's size and date keep their columns
  (the name was padded by the mark's bytes, `✓` being three). The comparison against the designs
  confirms the header's right-aligned state block, the chip tabs, the upload dialog's rows and
  columns, the transcript's turn rows and the settings rows. The queue page still differs: the
  design has no outer panel - a `Queue 3 tasks ... ● Running   Space pause` header and a rule, then
  two-line rows (file, language and size; status, phase and progress) - its upload area is a titled
  `╭─ Upload ─╮` box with the choose line and a `New uploads: language ... speakers ... max ...`
  line, the notices sit under a `Finished` heading, and the footer is one compact line. Those are
  the next design pass, with the history screen behind them.
  Verification: 221 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green.
- 2026-11-15 (the user's reports): in the upload dialog Tab moves between the files and the settings
  area, left and right pick Language or Speakers there, Up from a chip returns to the files and
  Down opens the focused chip's list; a chip or its list in focus leaves no file highlighted, and
  the arrows then move the list alone. The chips' values change only through their lists (the
  Left/Right cycling is gone), and Tab on a typed path completes it - the folder the path names,
  its matching entries' longest common prefix, a folder with its slash, a single file whole - so
  Enter can send it at once (`Browser::complete`). The options tests follow the new keys;
  `upload_options.rs` and `upload_typed.rs` carry the new scenarios.
  Verification: 223 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green.
- [x] (basic) (discovered, the user 2026-11-15) The upload dialog's language list narrows as letters
      are typed and widens with Backspace - `client/stt-tui/tests/upload_options.rs`.
- [x] (basic) (discovered, the user 2026-11-15) A click on a file's checkbox ticks or unticks it -
      `client/stt-tui/tests/upload_dialog.rs`.
- [x] (basic) (discovered, the user 2026-11-15) A dialog's prose is muted with its keys in the
      accent, underlined, as the footer draws them, and no cell falls back to the terminal's
      default foreground (`clear` in `view.rs`; the settings dropdown's rows are checked in
      `client/stt-tui/tests/settings_dropdown.rs`).
- 2026-11-15 (the user's reports, continued): the upload dialog's language list filters as letters
  are typed and widens with Backspace (the filter shows in its title); the dialog's prose is muted
  with its keys in the accent, underlined, as the footer draws them; a click on a file's checkbox
  ticks or unticks it. The "garbage from the task" the user saw in the dialog was the page showing
  through it: clearing a dialog's area had been replaced by a styled block, which only recolours
  the glyphs already there, so the queue's running rows stayed in the dialog's blank cells - the
  erase now comes first and the cells take the page's own foreground (the cursor, upload and
  options tests were the repros). While a dialog is open the loop also repaints every cell every
  500ms, so text another process writes to a shared terminal (a backend started with `&`) is
  erased; the TUI cannot stop those writes, and `stt-app start` already logs the service to
  `<config>/service.log`.
  Verification: 225 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green.
- 2026-11-15 (the user's reports, continued): the upload dialog is now as big as its content - one
  row per entry above its fixed rows, capped by the frame's margins, and as wide as the widest thing
  it draws (the longest entry, the options row, or its own prose: the folder line, the mark hint,
  the typed path and its keys, which keeps a typed path from clipping), centred horizontally with
  the page's top margin; an option list clamps inside it. The details pass also gave the language
  list its filter, the dialog its muted prose with accent keys, the checkbox clicks, and the
  `clear` erase (the "garbage in the dialog" was the page showing through it; the loop also
  repaints every cell every 500ms while a dialog is open, which erases stray writes from a process
  sharing the terminal).
  Verification: 227 client tests in a full run (`--no-fail-fast`), clippy and `cargo fmt --check`
  green.
- 2026-11-15 (design pass, the queue screen): the queue page follows `docs/design/tui/queue-dark.svg`
  and `queue-light.svg`, compared at 100x30. No outer panel: a titled `╭─ Upload ─╮` box two
  columns in from each side (accent border, the title in the text colour) with the chooser line -
  `⇪  Choose audio files  —  u or Enter, or paste / drop a path into the terminal` - and the
  defaults new uploads take, `New uploads: language auto · speakers auto · max 8     change
  options`, read from the stored settings (the contract's defaults before the read; a fixed count
  shows as the speakers); a blank row; `Queue n tasks` with `● Running` (done colour) or `● Paused`
  (muted) and `Space pause`/`Space resume` at the right, the key withheld while the upload area has
  the focus (Space opens the dialog there); a rule; two lines a task - the name, then at the status
  column (62 from the right) a running task's phase and time in the accent or a status chip in its
  group's text and background colours (ADR 0033; a running task with no phase yet shows the
  `RUNNING` chip), a twenty-column bar (`━` per whole cell, `╸` for a half one, the track in the
  border role; the fill accent while running, muted otherwise, the done colour for the speakers
  stage) and the percentage right-aligned four columns in; under them `{language} · {size}` and,
  while diarizing, `Speakers` with the stage's percentage (the catalog's `Clustering 50%`) and the
  two-stage bar. The selected row has the accent `▍` and the accent tint across the page (`>`
  under `NO_COLOR`); the focused upload area is tinted inside its border instead of the old `>`.
  The finished tasks sit under the rows: a blank row, `Finished`, and a line each - `✓`/`✗`/`≡`/`⊘`
  in the status's colour, the name, the rest muted with its key (`transcribed — Enter open`); the
  rest of the page is empty. The keyboard follows the layout (the user, 2026-11-15): Down past the
  last row reaches the notices and Up from the first returns, Up from the upload area does nothing.
  At narrow widths the bar drops out first (80 columns), then the running phase gives way to its
  chip (60). `o` ("change [o]ptions") opens the settings as `s` does; it is not in the footer or
  the reference, as the design draws neither. The catalog gains the queue's and the upload box's
  words and the notices' `*-rest` texts and loses the table's column titles, `queue.title-paused`,
  `upload.area` and the old notice texts; `View` carries the stored settings instead of the theme
  mode alone; the palette gains the status groups' backgrounds; a dialog's muted prose writes no
  colour under `NO_COLOR`. Red: `tests/queue_design.rs` (new) timed out waiting for `New uploads:
  language auto · speakers auto · max 8` on the old bordered table. The tests the design changes
  were proposed and approved before they were written (the user, 2026-11-15): the page marker is
  `Choose audio files`; the notices read `… — Enter open`; `upload_area.rs` reads the tint, not
  `>`; `queue_rows.rs`, `attach.rs`, `commands.rs`, `queue_narrow.rs`, `running_progress.rs`,
  `diarizing_row.rs` and `theme_colours.rs` read the new row; `queue_notices.rs` and
  `duplicate_upload.rs` reach the notices with Down; `theme_switch.rs` and the journey's theme test
  wait on the `—` separator's border colour; `footer_click.rs` clicks the footer's `pause`, not the
  header's;
  `settings_scroll.rs` waits for the first task at 14 rows; `upload_dialog.rs` checks the
  dialog's own bottom corner; `terminal::footer_hints` also reads the rows under a blank row. One
  full run had `status_playback.rs` see `♪ playing` just after `b`; it passed alone three times and
  in the next full run (a timing race, not this change).
  Verification: 225 client tests passed and 3 ignored (the journey's) in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed,
  `vitest run` 315 passed and prettier clean over the frontend and `ui-text/en.json`.
- 2026-11-15 (refactors approved by the user after the queue step): the hit-test reads clicks with
  `Rect::contains`; a queue row's status and its speakers stage are their own functions
  (`row_status`, `draw_speakers_stage`); `terminal::Scene::slice` reads a row's columns for every
  pty test. Each its own commit, the tests green.
- 2026-11-15 (design pass, the footer; the proposal approved by the user the same day): a page's
  footer is one row, as the designs draw it - its hints whole while they fit, then `? more` in
  place of the rest (`hints::fit`; `[?] more` in the catalog). Only a page's own list compacts
  (`footer::compacts`: the page's list in the normal key mode), since `?` opens that page's
  reference; a dialog's, a prompt's, the notices', the Speakers panel's and "Move to"'s lists, and
  a page list while a dropdown, a number or a confirmation reads the keys, still wrap. The
  reference is the destination of `more`, so it now shows every entry: it is sized to them and
  flows into a second column when the frame is too short (the transcript's 24 hints were clipped at
  24 rows), and a click outside it is measured on that area (`view::help_area`). The queue's own
  `Space pause`/`Space resume` leaves the footer for the header, as the design's footer has none;
  the reference still lists it. Red: `footer_wrap.rs` (rewritten from "every hint whole on some
  row" to "the footer is the last row, the row above names none of the page's hints, and each
  hint is on it or, behind `? more`, in the reference") timed out on the queue's two-row footer at
  80 columns. Approved test changes: `queue_hints.rs` reads the queue's Space hint on the header;
  `key_hints.rs` takes `Shift-↑↓ or J/K reorder` as its non-letter example; the tests that read
  which hints a footer offers start at `terminal::WIDE` (400 columns, the transcript's whole list
  being 321) - `open_transcript_with`, `open_transcript_playing`, `reset_merges.rs`,
  `scrolling.rs`, `write_gate.rs`, `queue_hints.rs`; `scrolling.rs` counts its page as the TUI
  defines it, the turns whose header is in view (the one-row footer made the viewport 13 rows, so a
  cut-off turn's header now shows). Discovered (the user, 2026-11-15), added to the test list: the
  Settings page's introduction and setting names muted; the `?` reference as a Key/Description
  table framed and titled as the upload dialog; the page greyed out under an open dialog, whose
  frame takes the accent colour.
  Verification: 225 client tests passed and 3 ignored (the journey's) in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed,
  `vitest run` 315 passed and prettier clean over the frontend and `ui-text/en.json`.
- 2026-11-15 (the user's report, design pass): the Settings page's introduction and the names of
  the settings it does not have selected are drawn in the muted colour, as `settings-dark.svg`
  draws them; the selected setting's name is in the text colour, bold, and the values keep the text
  colour. No refactor at the footer step's checkpoint (the user). Red:
  `tests/settings_muted.rs` (new) timed out waiting for the introduction's muted foreground.
  Verification: 226 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-15 (the user's report, after btop; the proposal approved the same day): while a dialog
  is open - the key reference, a confirmation, the upload dialog, the download dialog, "Move to" -
  everything drawn under it (header, page, footer) takes the muted foreground, its backgrounds kept,
  and each dialog greys out the ones under it (`view::dim`, nothing under `NO_COLOR`); every dialog
  is framed as the upload dialog is, the border in the accent and the title in the text colour
  (`view::dialog_block`, which the upload dialog now shares). The settings dropdown and the upload
  dialog's chip lists are lists inside a page or dialog, not dialogs, and dim nothing. No refactor
  at the settings step's checkpoint (none suggested: one style per cell). Red:
  `tests/dialog_dim.rs` (new) timed out waiting for `memo.wav` in the muted colour under the key
  reference.
  Verification: 227 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-15 (the user's report, after btop's help; the shape approved the same day): the `?`
  reference is a table - a bold `Key:` and `Description:` header, each key in the accent, its words
  muted - in the dialogs' frame. A hint's key and words come from its catalog text
  (`hints::key_and_words`: a letter key inside its word keeps the word whole, `[p]ause` is `p` and
  `pause`; any other key leaves the words after it, `[Enter] open` is `Enter` and `open`; a hint
  without a key leaves the key blank). A table taller than the frame scrolls inside it with the
  arrows, `j`/`k`, PageUp/PageDown, Home and End (the user's choice over side-by-side blocks), its
  cut edges marked `↑ more` and `↓ more` on the frame; it opens at the top. This replaces the
  footer step's flowing columns. The table's words are in the catalog (`help.key`,
  `help.description`, `help.more-above`, `help.more-below`). No refactor at the dim step's
  checkpoint beyond the approved comment fix on `modal`. Red: `tests/help_table.rs` (new, two
  tests) timed out on the missing table and the missing `↓ more`. Approved test changes:
  `footer_wrap.rs` finds each hint left to `? more` as a table row, scrolling the reference down
  while it says `↓ more`; `help.rs` asserts table rows (`u`/`upload`, `Shift-↑↓ or J/K`/`reorder`,
  `Enter`/`open`), its old words being still found in the greyed footer under the dialog.
  Verification: 229 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, `vitest run` 315 passed and prettier
  clean over the frontend and `ui-text/en.json`.
- 2026-11-15 (the user's report: the transcript's reference showed `Enter` twice, for `play from
  here` and `move to…`): the `?` reference lists only the page's keys that act now - the footer's
  rules, and while the stream is down without the writes - in the footer's order: those the footer
  shows, those it leaves to `? more`, and the queue's `Space pause`/`resume`, which the footer leaves
  to the header (`footer::reference`; the footer drops the header's keys by name, `IN_HEADER`, so
  their rules act again). The reference's geometry reads the view (`help_area`, `help_max_scroll`).
  Refactor approved at the help step's checkpoint and committed on its own: one table-row helper
  for the reference's tests (`terminal::table_cells`, `terminal::table_row`). Red: the new
  `help_table.rs` test failed on `Enter` / `move to…` listed with nothing marked. Its scrolling test
  now runs at 16 rows, since the transcript's acting keys fit 24; the new test keeps the service up,
  as `n new speaker` is a write.
  Verification: 230 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-15 (design pass, the History screen; the proposal approved by the user the same day): the
  History page follows `history-dark.svg` and `history-light.svg` at 100x30. No panel: the controls
  row - `/` and `f` in the accent, `Search` and `Status` muted, the search field 28 columns on the
  raised surface showing the query (the terminal's cursor in it while a search is typed), the
  filter's chip on the raised surface with the catalog's label and `▾`, and `n of m tasks` muted -
  then a rule, then one line a task: the status chip at column 2 in its group's colours (as the
  queue's), the name at 15 (bold when selected, ellipsised before the date), and the date and
  duration muted 39 columns from the right edge, the selected row with the accent `▍` and the tint
  across the page. Under a row: `⚠ error` in the problem colour at 17 and, kept beside the design
  (the user), a missing clip's note muted; `♪ playing` follows the name. No matches: the catalog's
  line under the rule; empty: the tile and its words without a panel (`draw_empty` and its panel
  are gone). The list keeps the selected row in view from the row it showed before
  (`history_offset`), and the hit-test reads rows from it. The catalog gains `history.search`,
  `history.status` and `history.count` and loses `history.controls`. No refactor at the reference
  step's checkpoint (the user). Red: `tests/history_design.rs` (new) timed out waiting for `/ Search`
  on the old bordered page. Approved test changes: the History page marker `Search: ` /
  `Search: —` is `/ Search`; `history_search.rs` reads its query and filter on the controls row
  (`<label> ▾`); `transcript_delete.rs` waits for `All statuses ▾`; the light theme test reads
  History's rule.
  Verification: 231 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, `vitest run` 315 passed and prettier
  clean over the frontend and `ui-text/en.json`.
- 2026-11-15 (design pass, the header; the proposal approved by the user the same day): the header
  follows every design's first three rows - its first two rows on the surface band across the page,
  the tile's column on the accent tint down all three, the product name in the accent and bold,
  beside it muted `transcribing 1 task` / `transcribing N tasks` while tasks run and `idle`
  otherwise (`header.transcribing-one`, `header.transcribing-many`, `header.idle`), and the rule
  under the tabs in the border colour. The bands follow the SVGs, which paint the surface on rows 0
  and 1 and the tint on the tile's column (the brief had them the other way round). `● Live` and the
  theme label stay as they are. Refactor approved at the History step's checkpoint and committed on
  its own: one `status_chip` for the queue's and History's rows. Red: `tests/header_design.rs` (new)
  timed out waiting for the name in the accent.
  Verification: 232 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed, `vitest run` 315 passed and prettier
  clean over the frontend and `ui-text/en.json`.
- 2026-11-15 (the user's review after the header step: "The rest is good", with two reports): the
  Settings page's `Restore default settings` row is muted while it is not selected and in the text
  colour, bold, when it is, as the settings' names are. No refactor at the header step's checkpoint
  (none suggested). The remaining design differences (the footer's `·` separators and band, the
  designs' shortened hint words and `[m]ore`, `[t]heme:` and the name's indent, History's `x clear
  filters`) stay as they are (the user). Red: `settings_muted.rs`'s new assertion found the row in
  the text colour.
  Verification: 232 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-15 (the user's report, after btop): the `?` reference's keys and its `Key:` header are
  centred in their column, the descriptions keeping one left edge. Refactor approved at the restore
  step's checkpoint and committed on its own: one `settings_name_style` for a Settings row's name
  and the restore row. Red: the table test's new assertion (`p` seven columns into a 15-column key
  column, where `Shift-↑↓ or J/K` starts it) found the key at the column's start; it replaces the
  assertion that both keys start in the same column.
  Verification: 232 client tests passed and 3 ignored in a full run (`--no-fail-fast`), clippy and
  `cargo fmt --check` green, `gradle tuiJourney` 3 passed.
- 2026-11-15 (workspace): the designs line is ticked - the queue, the footer, the header and
  History follow their designs, with the user's reports along the way (the muted Settings words,
  the greyed page under a dialog, the key reference as a scrolling, centred Key/Description table
  listing only the keys that act), and the user's review closed it ("Perfect."; the remaining
  differences from the SVGs - the footer's `·` separators, the shortened hint words, `[t]heme:` -
  stay, the user 2026-11-15). Every test-list line is ticked; `Status: in-review`. Handed to the
  user for review: not pushed, not merged.
  Verification: as the centring step's - 232 client tests passed and 3 ignored in a full run
  (`--no-fail-fast`), clippy and `cargo fmt --check` green, `gradle tuiJourney` 3 passed; the last
  `vitest run` (315 passed) and prettier over the frontend and `ui-text/en.json` ran at the header
  step, the catalog's last change.
- 2026-11-15 (workspace, after the merge of `main` `f30e7a61` as `12bac98f`; the user's decisions
  at the checkpoint): the TUI-localization line item 88 promised is approved as worded and added
  under "Localization". `frontend/src/locales/*.json` is the single UI text catalog (the user,
  earlier the same day): the Rust clients read it, `ui-text/` goes, and ADR 0067 is amended. Item
  88's checks require every key of `en.json` in every shipped catalog with a review row, so the
  TUI's keys cannot enter the catalog in English alone; offered staging them under a relaxed
  `tui.*` rule or keeping `ui-text/` until translations exist, the user chose to move the catalog
  inside this line's cycle with every TUI string translated into all 27 other locales and recorded
  on their review pages under item 88's translation standard. The TUI reuses a browser key where
  the meaning is the same, and its six keys whose names clash with browser keys of another meaning
  are renamed. `Status: in-progress` until the line is green and the user confirms.
- 2026-11-15 (workspace, the localization cycle; `Status: in-review`): the user approved the
  localization test-list line as worded and, at the refactor checkpoint, the three cleanups (one
  catalog lookup for `text_in`/`template`, one `Locale` literal in `set_locale`, a `DateFormat`
  enum instead of a `month_day` bool), committed as `c08e92e4`. Every gate is green; the line is
  ticked. Not pushed, not merged.
- 2026-11-15 (workspace, the root review's findings; `Status: in-progress`): the review found one
  blocker (two default-terminal keys the catalog move dropped), two documentation defects
  (`themeMode` missing from the HTTP contract; a stale known-issues row) and two cleanups (ADR
  0068 describing code that does not exist; stale references in this file), to be worked in that
  order with a checkpoint before each. The user approved the discovered default-terminal line as
  worded, chose `Terminal` in all 28 catalogs over Apple's localized display names (`PowerShell`
  is never localized), and took no refactor after its green (none suggested: a two-key repoint
  and a four-line test). For the contract, the user widened the fix: the review's premise that
  the spec documented every other settings field was wrong - item 66's `defaultFrontend` and
  `terminalCommand` were missing from the field list and from `DELETE /api/settings`'s defaults
  (`SettingsRepository.reset` writes `DEFAULT_SETTINGS`, which nulls both) - and they are
  documented in the same commit as `themeMode`.
- 2026-11-15 (workspace, the stale known-issues row): `129da832` had fixed the unknown-key `PUT`
  (`InternalError.kt:28-32` answers `400 invalid-request-body`, pinned by `SettingsUpdateSpec`)
  but removed a different row, so the 500 row stayed. Its one open part - whether unknown keys
  should be ignored - is already answered by the HTTP contract since 2026-11-09 (a key the
  request does not know → `400 invalid-request-body`); the user chose to delete the row.
- 2026-11-15 (workspace, ADR 0068 against the code): offered amending the ADR to the code
  (recommended) or implementing its 256-colour fallback, the user chose to implement it, approved
  the discovered line with Windows always in 24-bit colour and the smallest RGB distance, and
  the ADR gained an amendment for what was still untrue (`include_str!` in `theme.rs`, not
  `build.rs`). At the refactor checkpoint the user approved all three suggestions, each its own
  commit with the client's gates green (237 passed, 3 ignored after the last): the tokens parsed
  once per palette instead of once per token (`48bc6d62`); a token's parsing (`rgb`) split from
  how the terminal draws it (`drawn`) (`48f5087b`); and true colour as `for_mode`'s own argument,
  read by `Ui::new` beside `NO_COLOR`, so `Clues` holds only what `auto` decides from
  (`2e955fcf`).
- 2026-11-15 (workspace, stale references in this file; the user's go): the theme-storage line,
  cycle 59 and the 2026-11-08 e2e entry name the theme migration as `V19__theme_mode.sql`, noting
  it was `V18` until the merge of item 88; the TUI-localization line names `localization.rs`, and
  the split playback and running-row lines name the test each one's cycle added
  (`playing_segment.rs`, `segment_playback.rs`, `history_playback.rs`, `no_audio_device.rs`,
  `no_audio.rs`, `playback_stops.rs`; `running_row.rs`, `running_progress.rs`,
  `diarizing_row.rs`).
- 2026-11-15 (workspace, `Status: in-review`): the user confirmed the hand-over after the gates.
  The journey output the user pasted with the confirmation (62 passed, 1 skipped, both `66-*`
  specs green) ran in `workspaces/88-localization` - its service is that worktree's install and
  its database stops at V18 (`app language`), without this branch's V19 - so it is not evidence
  for this branch: `66-launch-token.spec.ts` and `66-settings.spec.ts` stay the user's to run in
  this worktree, with CI's macOS and Windows `cargo test` legs. Not pushed, not merged.
- 2026-11-16 (workspace, the user): reopened, `Status: in-progress`, for the PR's CI run #660
  (five failed jobs in the pty suite), in three clusters - lost keys, Windows startup, test
  synchronization - with the journey's `Task 1 has no speaker 1` investigated on its own. The
  status is set in this file, which the squash had already moved from `roadmap/`.
- 2026-11-16 (decision, the user): lost keys are fixed with crossterm's `use-dev-tty` event
  source, not an own stdin reader (which would need a second escape parser - crossterm's is
  private); the new line "a key pressed right after a terminal resize is applied" is approved.
- 2026-11-16 (decisions, the user, after the diagnostic run): the harness answers ConPTY's cursor
  query, and the next dispatch carries a marked diagnostic printing the journey's turns before
  its merge; the stand-in service stores the theme mode `dark` unless a test stores another (no
  product knob); a typed path cut off at its field's edge is a product defect with its own line;
  the refused upload's frame is fixed in the product (the upload's line goes before its notice).
- 2026-11-16 (decisions, the user, on the run 43802779592): the refactors `typed_field` and
  `settings_in` approved; `upload.rs` waits for a long typed path's end and `upload_area` for the
  stored mode, following the approved lines; a typed path's error clipped at the dialog's border is
  a product defect with its own line; the light flash at startup goes to `docs/known-issues.md`,
  not fixed in this item.
- 2026-11-16 (decision, the user): the journey's merge test uploads `jfk.wav` said twice, apart,
  rather than a new two-voice fixture.
