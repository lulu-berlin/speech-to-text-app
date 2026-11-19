# 0068 — One theme-token file for every frontend

Status: accepted (amended 2026-11-15: how the TUI reads the file, and when it draws 256 colours)
Date: 2026-11-07

## Context

The browser (React, `frontend/`) colours its light and dark themes from token sets in
`frontend/src/shared/theme/theme.ts`; its WCAG contrast tests (`contrast.test.ts`,
`speakerTints.test.ts`) pin them. The TUI (Rust, `client/stt-tui`) was monochrome. The user asked
(2026-11-07, item 67's second amendment) that the TUI be styled in colour with a dark and a light
mode, and chose that both frontends take their colours from one shared file — as the user-visible
text already does ([ADR 0067](0067-one-ui-text-catalog-for-every-frontend.md)) — rather than the TUI
copying the browser's values.

## Decision

- **One token file**: `ui-theme/colors.json` at the repository root holds the colour tokens of the
  `light` and `dark` themes — every entry of `ThemeTokens.colors`, the status, speaker and
  illustration colours included — as hex strings. Non-colour tokens (spacing, type, radii, shadows,
  sizes) stay in `theme.ts`: only colours are shared, because only colours are meaningful to a
  terminal.
- **The browser reads it directly**: `theme.ts` builds `lightTheme.colors` and `darkTheme.colors`
  from the file (JSON modules are enabled), keeping the `ThemeTokens` type, so the contrast tests
  keep testing the same values and a missing or misspelt token fails the type check.
- **The TUI reads it at build time**: `client/stt-client/build.rs`, which already compiles the text
  catalog, compiles the colour file into constants; the TUI maps tokens to 24-bit colours, and to
  the nearest 256-colour entry where the terminal reports no true colour. `NO_COLOR` still disables
  every colour.
- **The theme mode is one stored setting, shared by every frontend** (the user, 2026-11-07: dark
  vs light is sticky across the browser and the TUI): `themeMode` (`auto`, `light` or `dark`;
  `auto` by default) is part of the service's stored settings, so `GET`/`PUT /api/settings` and the
  `settings-updated` event carry it. The browser's toggle and the TUI's `t` key both write it, and
  either frontend follows a change made in the other live. This amends
  [ADR 0033](0033-design-system-tokens-radix-plex.md), which stored the browser's mode in
  `localStorage`; the browser keeps only the last-known mode there, to paint the first frame before
  the settings arrive. The TUI has no `--theme` argument.
- **`auto` resolves per frontend, from the OS first**: the browser follows `prefers-color-scheme`;
  the TUI asks the OS for its preference — the freedesktop `color-scheme` (GNOME's
  `org.gnome.desktop.interface color-scheme`) on Linux, `AppleInterfaceStyle` on macOS,
  `AppsUseLightTheme` on Windows — then the terminal for its background colour, and falls back to
  dark.

## Consequences

- A colour change is one edit in one file, and both frontends follow it; the browser's contrast
  tests guard the shared values for both.
- The Rust build depends on a second repository file outside the crate; like the catalog, a
  missing file fails `build.rs` loudly and every build path builds from the checkout.
- The TUI cannot express everything the browser draws (gradients, shadows, alpha): it uses the
  colour tokens only and leaves the rest to the terminal.
- The settings contract grows by one field: a schema migration stores `themeMode` (existing
  databases read `auto`), `docs/api` gains it in the settings examples and the `settings-updated`
  event, and a value other than `auto`, `light` or `dark` is refused like any invalid setting.

## Amendment (2026-11-15)

The root review of item 67 found that the TUI did not do what the Decision's TUI bullet says. The
file is not compiled by `client/stt-client/build.rs`, which compiles only the text catalogs and
`docs/api/ui-locales.json`: `client/stt-tui/src/theme.rs` takes `ui-theme/colors.json` in with
`include_str!` and parses it when it builds a palette. A missing file still fails the build; a
malformed file or a missing token fails the TUI's theme tests (`tests/theme_colours.rs`), not
`build.rs`. The 256-colour fallback was missing too; the user chose (2026-11-15) to implement it
rather than drop it from this ADR, and it now behaves as follows:

- The terminal reports true colour with `COLORTERM` set to `truecolor` or `24bit`; then every
  token is drawn as its exact 24-bit colour. The Windows console always gets 24-bit colour:
  conhost (since Windows 10 1703) and Windows Terminal draw it, and neither sets `COLORTERM`.
- Any other terminal gets each token's nearest entry of the 256-colour palette by RGB distance,
  chosen from the colour cube (16-231) and the grey ramp (232-255). The 16 system colours are
  never chosen, because the user's terminal theme redefines them.
- `NO_COLOR` still draws no colour at all.
