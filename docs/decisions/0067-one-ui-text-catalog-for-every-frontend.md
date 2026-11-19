# 0067 — One UI-text catalog for every frontend

Status: accepted (amended 2026-11-15: the catalog lives in `frontend/src/locales/`)
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

- **One catalog**: `frontend/src/locales/<locale>.json` is the single source of truth for the text
  a frontend owns. It is a flat map of keys to ICU messages, `"queue.title": "Queue"`, with
  `{name}` placeholders and `{count, plural, …}` counts substituted by the caller. One file per
  locale, and a missing key falls back to the locale's file, then to English, then to the key
  itself, which is visible in a test rather than silent.
- **Keys are marked, not spelled beside their action**: a template that names a key brackets it -
  `[p]ause`, `re[v]ert`, `[Enter] open` - so a letter key sits inside its action's word and any
  other key before its action. The TUI draws a marked key underlined in the accent colour without
  the brackets, and keeps the brackets under `NO_COLOR`, which writes no attributes
  (`client/stt-tui/src/hints.rs`). The markup is read before placeholders are filled, so a value
  such as a file name with brackets in it is never taken for a key. A translation (item 88) marks
  the key's letter in its own word, or puts the key in front where the word lacks it.
- **The browser reads it directly**: `frontend/src/shared/i18n/` loads the catalogs and
  `useMessages()`/`t()` read them through `react-intl` (item 88), with the App language's locale
  and its `Intl` formats.
- **The TUI reads it at build time**: `client/stt-client/build.rs` turns the same files into Rust
  constants compiled into the crate; `stt_client::text(key, args)` looks a key up, fills its
  placeholders and chooses its plural, with the same fallback, and `stt_client::set_locale` picks
  the catalog and the format locale from the stored App language. A change to a catalog rebuilds
  the crate (`cargo:rerun-if-changed`).
- **The frontends' adoption is incremental**: item 88 migrated the browser's text; item 67 routes
  the TUI's strings through the same catalogs, translated into every shipped locale.
- **Tests read the catalog, they do not repeat it**: a frontend test asserts what it renders is
  the catalog's text for its key (the TUI's pty test, the browser's component test), so changing a
  wording in one place changes both and the tests stay honest.
- **Server text stays server-owned**: error `message` values are shown as received; a client never
  re-words them.

## Consequences

- A new user-visible string in either frontend is a catalog entry plus a lookup; a literal in
  feature code is the exception and needs a reason.
- Adding a locale is one JSON file plus locale selection (item 88); no code knows English.
- The TUI's strings are part of the same catalogs, so a locale's review page covers them too, and
  the catalog checks (key parity, ICU, plural categories) hold for the terminal as for the browser.
- The Rust build now depends on a repository file outside the crate: the Nix, CI and local builds
  all build from the checkout, and `build.rs` fails loudly when the file is missing.
- A TUI-only string is a `tui.*` key: the terminal's hints, dialogs and notices have no browser
  counterpart, and the prefix keeps them apart from the browser's own keys.

## Amendment (2026-11-15)

Item 88 landed first and built the catalogs, the checks, the review pages and their generator
(`frontend/src/locales/*.json`, `docs/reviews/`, `docs/generate-reviews.ts`,
`docs/check-reviews.ts`). The user decided (2026-11-15) that those catalogs are the one place the
text lives, so this ADR's principle stands and its location moves: `ui-text/` is gone, the Rust
clients read `frontend/src/locales/<locale>.json`, and the TUI's strings entered the catalogs
translated, with their review rows, in the same change. The theme tokens stay where ADR 0068 put
them (`ui-theme/colors.json`).
