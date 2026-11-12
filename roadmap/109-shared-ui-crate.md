# 109 — One UI core for every frontend: the `stt-ui` crate

Status: draft
Depends on: [67](67-tui-frontend.md) (the TUI's flows, state and playback modules), [70](70-rust-gui-window.md) (the Slint window reaches the same feature set), [88](88-localization.md) (the catalogs, glossary and review records), [98](98-rust-workspace-at-root.md) (the crate lives in `crates/`)
Blocks: — (the Rust browser frontend and the mobile frontends are later items built on it)

> Numbering note: items 100–108 (the Rust backend rewrite) are filed on the unmerged
> `docs/rust-backend-phase-1` branch, so this item takes 109 to avoid a collision.

## Goal

Every frontend is built from one shared Rust core instead of reimplementing the app: state
machines, the localization layer and keys, and the user-facing features as abstractions. The TUI
(item 67) and the Slint window (item 70) become thin renderers over it, and a future Rust browser
frontend and mobile frontends reuse the same core for their own layouts. The core stops at
**interactions, not pages**: queueing an audio file, transcribe-and-diarize, editing a segment,
playback, merging and reassigning speakers — not tabs or screens, because a mobile layout may
group the same interactions differently.

## Evidence

- **Two frontends already duplicate the same logic.** Item 67's branch carries
  `client/stt-tui/src/state.rs`, `player.rs`, `transcript.rs`, `writes.rs`, `upload_options.rs` and
  `footer.rs`: queue and task state, playback state, transcript navigation, write-in-flight rules
  and the upload options — all of it would be written a second time in Slint's Rust for item 70,
  which promises "parity with the browser UI" including playback and drag-and-drop upload.
- **The browser is the third copy**: the React app holds the same flows (`frontend/src/`), and item
  70's evidence already records that the terminal and the window must reach the same parity. A
  fourth and fifth frontend (WASM browser, mobile) would multiply the drift.
- **The client transport is already shared** (`stt-client`: discovery, credential, tasks, queue,
  transcripts, speakers, merges, settings, SSE with reconnect), so the next shared layer is the one
  above it — state and features, not HTTP.
- **Localization is a frontend-wide concern** (item 88): catalogs, ICU messages, plural
  categories, RTL, error-by-code, review pages. A Rust frontend needs exactly the same data, so
  the catalog is a single source, not one per language of implementation.
- **The workspace layout is item 98's**: `crates/stt-client`, `crates/stt-app`, `crates/stt-tray`,
  `crates/stt-tui`; this crate joins them as `crates/stt-ui` with the same pinned toolchain and
  committed `Cargo.lock`.
- **Mobile is a feasibility question** (item 86): it decides whether a phone target exists, not
  whether the core is frontend-agnostic — the abstraction is worth it for the four frontends
  already planned.

## Decided (draft, 2026-11-12)

- **All frontends become Rust** (the user): the TUI, the window, eventually the browser; the core
  exists so each target implements rendering and platform input, not the app's behavior.
- **The core holds**: the finite state machines, the texts as localization keys, the localization
  library, and the UI features as abstractions.
- **It stops at interactions, not pages** (the user): "queue audio file", "transcribe + diarize",
  "edit segment", "playback", "merge/reassign speakers" — no tabs or page structure, since a mobile
  split may differ.

## Proposal

1. **The crate** `crates/stt-ui`, framework-agnostic and renderer-free: no terminal, window or
   browser types, no I/O beyond what the feature needs, one entry point per feature.
2. **Modules**:
   - `flow` — the finite state machines (app/queue/upload/transcript/playback/settings/speakers),
     with explicit states, events and commands, and illegal transitions rejected by construction;
   - `feature` — the interaction abstractions, each a small API over `stt-client` plus a state
     machine: `queue_audio` (pick, options, upload, duplicate/rejection by code), `run_task`
     (transcribe + diarize: phases, progress, pause/resume/cancel/retry), `edit_segment` (select,
     edit text, save, revert), `playback` (play/pause/seek/follow, sink behind a trait),
     `speakers` (rename, merge, reassign, new speaker);
   - `i18n` — the message keys, the catalogs and the lookup/format rules (ICU, plurals, dates,
     numbers, RTL direction), shared by every frontend;
   - `model` — the view models the renderers draw (rows, phases, badges, footer hints), still free
     of widgets.
3. **The localization single source**: the catalogs move from the React app (item 88's work) into
   `i18n` as the canonical data; the React frontend consumes them generated (JSON compiled into
   its bundle), so item 88's review pages, glossary and checks keep working over the same files.
4. **Migration in one item, in order**: build the core from the TUI's modules (they are the newest
   and least entangled), move the TUI onto it, then build the window on it. The browser keeps its
   current implementation until its Rust port; only the catalogs are shared with it now.
5. **Testing at the core**: the FSMs and features are tested headlessly — a fake service (the
   `stt-client` trait) and a fake sink/clock — so a behavior proves once for every frontend; each
   renderer keeps its own rendering tests and the journeys.

## Open questions

- **The name**: `stt-ui` (with `stt-client`, `stt-tui`), `stt-core`, or plain `ui` in `crates/`?
- **The client seam**: does the core depend on `stt-client` directly, or on a trait it defines so
  tests and the future WASM target inject their own transport?
- **How far the state machines go**: one top-level machine with sub-machines, or one machine per
  feature composed by the renderer? The user's "no pages" rule suggests per-feature machines plus a
  small app-level router the renderer owns.
- **Platform audio**: the `playback` feature owns the state and the position derivation, but the
  sink is a trait implemented per target (cpal in the TUI, Slint's audio in the window, WebAudio in
  the browser). Confirm that boundary rather than a single backend in the core.
- **i18n in Rust**: `fluent`/`unic-langid` versus `rust-i18n`/ICU4X, and whether the React side keeps
  formatjs while reading the same ICU data. The key set and plural rules must match item 88's
  checks exactly.
- **Scope of the migration**: both frontends in this item (the proposal) or the TUI first with the
  window as its own follow-up item? The TUI is unmerged work in flight, which argues for TUI-first.
- **Mobile constraints**: whether the core must stay `no_std`-friendly or avoid async runtimes that
  a mobile target cannot host; item 86's outcome decides.
- **The catalog's location**: `crates/stt-ui/i18n/<locale>.json` (shared file format with the
  frontend) versus a Rust-native message format with a generated JSON for the browser.

## Test list (proposed; behaviors only, to approve at the consultation)

- [ ] (queue) Picking audio files and uploading them from the core yields the uploading state, the
      progress, the created tasks and a duplicate or rejected file answered by its error code — with
      a fake service and no renderer.
- [ ] (task) A task's transcribe-and-diarize phases and progress reach the core's state in order,
      and pause, resume, cancel and retry are commands the core accepts only in the states that
      allow them.
- [ ] (transcript) Loading a transcript, selecting segments, following the playhead and leaving
      follow mode are core states; an edit and its save/revert produce the same write outcomes as
      today, and a write in flight rejects a second one.
- [ ] (playback) Play, pause, seek and position derive from the core's clock and the sink trait,
      tested with a fake sink and clock, with the same behavior the TUI's footer and the browser
      player have today.
- [ ] (speakers) Rename, merge, reassign to an existing speaker and reassign to a new speaker are
      core commands whose outcomes match the API's, including the "no merge with nothing to merge"
      footer state.
- [ ] (i18n) Every shipped locale's catalog has the same keys, valid ICU messages, the plural
      categories its language requires and intact placeholders; a refused action resolves to the
      catalog's message by code and falls back to the service's English text.
- [ ] (i18n) Dates, numbers, times and plurals format through the library per locale, and an RTL
      locale reports a right-to-left direction the renderers use.
- [ ] (tui) The TUI draws from the core's view models and sends only commands: its existing tests
      move onto the core and stay green, with no behavior change.
- [ ] (window) The Slint window reaches its item-70 parity through the same core: upload, queue,
      transcript, playback, speakers and settings, proven at the core seam plus its own tests.
- [ ] (browser) The React frontend's catalogs are generated from the shared source, and its
      existing suite stays green with unchanged English text.
- [ ] (must not break) Both frontends' journeys stay green and no screen's behavior changes; the
      core's tests run without a terminal, window or browser.

## e2e stance

Exempt from ADR 0046's per-feature e2e test as a refactor-only item: no app behavior changes, and
the TUI's and window's existing journeys are the proof; the items that add frontends (WASM browser,
mobile) owe their own.

## Approval log

- 2026-11-12: filed as a draft by the root agent at the user's request: a `stt-ui` crate with the
  shared state machines, localization keys and library, and the UI features as abstractions for
  every Rust frontend, stopping at interactions rather than pages; to be started after 67 and 70.
