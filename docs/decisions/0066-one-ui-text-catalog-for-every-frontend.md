# 0066 — One UI-text catalog for every frontend

Status: accepted
Date: 2026-11-06

## Context

The browser (React, `frontend/`) and the TUI (Rust, `client/stt-tui`) are frontends of the same
service, and they show the same words: the queue's title, the commands' verbs, the upload prompt,
the notices. Today each carries its own literals — the browser inline in its TSX, the TUI in Rust —
so the two drift, and a wording fix is two edits in two languages.

Roadmap item 88 will localize the app, and the user asked (2026-11-06, item 67 consultation) that
the text be prepared for that now: keys and templates, not sentence fragments built in code, and
no assumption that there is only one language even while only English exists.

The service's own `message` fields in error bodies (`docs/api/error*.json`) are contract data: they
are behavior the API tests pin, not client-owned text, and they stay as they are.

## Decision

- **One catalog**: `ui-text/en.json` at the repository root is the single source of truth for the
  text a frontend owns. It is a flat map of keys to templates, `"queue.title": "Queue"`, with
  `{name}` placeholders substituted by the caller. One file per locale — `ui-text/en.json` today;
  item 88 adds the others — and a missing key falls back to the locale's file, then to English,
  then to the key itself, which is visible in a test rather than silent.
- **The browser reads it directly**: `frontend/src/shared/ui/text.ts` imports the catalog (JSON
  modules are already enabled in `tsconfig`), exposes `t(key, params?, locale?)` and types `key`
  against the catalog's keys, so a typo fails the type check.
- **The TUI reads it at build time**: `client/stt-client/build.rs` turns the same file into Rust
  constants (`MESSAGES`) compiled into the crate; `stt_client::text(key, args)` looks a key up and
  substitutes its placeholders, with the same fallback. A change to the catalog rebuilds the crate
  (`cargo:rerun-if-changed`).
- **The frontends' adoption is incremental**: item 67 routes the TUI's strings and the browser's
  queue and upload text through the catalog; the remaining browser text migrates in a follow-up
  item, whose test list says so.
- **Tests read the catalog, they do not repeat it**: a frontend test asserts what it renders is
  the catalog's text for its key (the TUI's pty test, the browser's component test), so changing a
  wording in one place changes both and the tests stay honest.
- **Server text stays server-owned**: error `message` values are shown as received; a client never
  re-words them.

## Consequences

- A new user-visible string in either frontend is a catalog entry plus a lookup; a literal in
  feature code is the exception and needs a reason.
- Adding a locale is one JSON file plus locale selection (item 88); no code knows English.
- The Rust build now depends on a repository file outside the crate: the Nix, CI and local builds
  all build from the checkout, and `build.rs` fails loudly when the file is missing.
- The browser's remaining literals still live in TSX until the follow-up item; the catalog is the
  place to add to in the meantime.
