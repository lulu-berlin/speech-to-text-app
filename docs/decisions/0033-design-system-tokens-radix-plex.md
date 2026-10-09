# 0033 — Design system: tokens, Radix primitives, IBM Plex Sans

Status: accepted · Date: 2026-10-09

## Context
Item 13 built the frontend foundation with almost no visual design: a theme of four colors, one spacing unit, two radii and one font size, and placeholder views. Items 14 and 15 would otherwise be built with ad-hoc styles and restyled afterwards. The user asked for a design system before those items, and decided its shape in the item 24 consultation (2026-10-09). The existing constraints are [ADR 0001](0001-stack-and-monorepo.md) (React, TypeScript, styled-components) and the [frontend conventions](../conventions/frontend.md) (all design values are theme tokens; no literal colors or sizes in components; shared components in `src/shared/`).

## Decision
- **Tokens** in `src/shared/theme/theme.ts`, one set for light and one for dark, as plain TypeScript objects (no CSS custom properties): semantic colors (`background`, `surface`, `surfaceRaised`, `text`, `textMuted`, `border`, `accent`, `accentText`, `focusRing`, `danger`), five status groups, a type scale, a spacing scale, radii, elevation, motion and breakpoints. Anything styled-components cannot reach (`::backdrop`, focus rings) is styled inside the styled components, so no component holds a literal color or size.
- **Typeface: IBM Plex Sans** (OFL), self-hosted as a woff2 in the repository with its licence notice, latin subset; the system stack stays as fallback.
- **Interactive primitives use Radix** (`@radix-ui/react-dialog`, `-tooltip`, `-dropdown-menu`, `-switch`, `-slider`) for behavior and accessibility only; all styling comes from the tokens. Icons come from **`lucide-react`**.
- **Layout primitives** (`Page`, `Stack`, `Row`) are part of the system, so the spacing rhythm is enforced by code rather than by convention.
- **Dark mode follows the OS with a manual override** (`auto → light → dark`), stored in `localStorage`.
- **Accessibility is part of the primitives**: AA contrast in both themes, visible focus rings, full keyboard operation, `prefers-reduced-motion`, correct roles and labels.

## Consequences
- Four new frontend dependencies (five Radix packages, `lucide-react`) and a font file: each changes `pnpm-lock.yaml` and the flake's `fetchPnpmDeps` hash, verified by the user (ADR 0012).
- Items 14 and 15 build their views from these tokens and primitives; item 25 is the final visual pass; item 17 (the mascot) uses the same palette.
- A new component cannot introduce a color or size of its own without failing the convention check.
- The self-hosted font must be shipped by the Nix package and its licence recorded next to the asset (ADR 0026).
