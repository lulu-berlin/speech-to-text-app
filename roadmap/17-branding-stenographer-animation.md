# 17 — Branding: stenographer typing animation

Status: draft
Depends on: [13](../docs/specs/frontend-foundation.md) (done), [24](24-visual-design.md)

## Goal

Give the frontend a recognizable brand: an animation of a stenographer typing, shown in the app shell as the app's logo/mascot.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components frontend, served by the backend.
- [ADR 0012 — pnpm](../docs/decisions/0012-frontend-dependency-management-pnpm.md): any new frontend dependency goes through pnpm and the Nix `fetchPnpmDeps` hash.
- [ADR 0015 — Code formatting](../docs/decisions/0015-code-formatting-ktlint-prettier.md); [frontend conventions](../docs/conventions/frontend.md): design values come from theme tokens, no literal colors or sizes in components, named exports, colocated tests.

## Proposal (pending approval)

- **Format: inline SVG animated with CSS keyframes** (styled-components `keyframes`), packaged as one React component (`StenographerAnimation`) under `frontend/src/shared/brand/`.
  - Why: a few KB, sharp at any size, colors bound to theme tokens, no runtime dependency, works offline in the packaged app.
  - Alternatives: Lottie (needs `lottie-web` and an authoring tool; larger), animated WebP/APNG/GIF (raster, fixed colors, larger), video (too heavy for a logo).
- **Motion:** a seated stenographer at a stenotype machine. The hands press chords in a loop, keys dip, and a paper tape (or caption line) advances. The loop is seamless, and only `transform` and `opacity` are animated (cheap, no layout work).
- **Placement:** the app shell header (item 13), next to the product name "Speech-to-Text". The header size is decided in the open questions.
- **Accessibility:**
  - Under `prefers-reduced-motion: reduce`, a static frame is shown instead of the animation.
  - The animation is decorative next to the visible product name, so it is `aria-hidden`. If it ever stands alone, it gets `role="img"` and an accessible name.
  - No flashing more than 3 times per second (WCAG 2.3.1).
- **Static derivatives** from the same artwork: an SVG favicon and a still image usable in docs.
- **Artwork source:** hand-authored SVG in the repo, so the license is clear. Any third-party art must have a license that allows redistribution in the repo and the Nix package; record it next to the asset.

## Open questions for the user

- Visual style: flat/minimal line art, detailed illustration, or pixel art? Colors from the theme, or a fixed brand palette?
- Stenographer depiction: a court stenographer at a stenotype machine (authentic, chorded keyboard), or a more generic typist (typewriter or laptop, more recognizable)?
- Who creates the artwork: the agent hand-authors the SVG (simple style), you supply an illustration, or it is commissioned or licensed?
- Placement and size: header logo only, or also a larger version on the empty-queue screen (item 14) or a loading state?
- Should the animation reflect app state (e.g. type only while a transcription is running, idle otherwise), or loop continuously as pure branding?
- Dependency: keep after item 13 (the app shell exists), or start right after item 02 by placing it in the current `App.tsx` header and moving it when item 13 builds the shell? The latter allows it to run in parallel now.
- Should item 16 (the Nix package) depend on this item, so the deliverable ships with the branding?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] The app header shows the stenographer animation next to the product name
- [ ] The animation is hidden from assistive technology while the visible product name is present
- [ ] With reduced motion requested, a static frame is shown and nothing animates
- [ ] Without a reduced-motion preference, the animation runs (animated parts carry an animation)
- [ ] The animation's colors come from theme tokens (changing a token changes the rendered color)
- [ ] The favicon is the static stenographer artwork

## Acceptance criteria

- The user approves the look and motion in a real browser (dev server and the packaged app), with and without the OS reduced-motion setting.
- The animation asset adds at most 15 KB gzipped to the frontend bundle, and no new runtime dependency unless approved.
- Smooth playback: no layout work per frame (only `transform`/`opacity` animated), checked in the browser performance panel.
- The artwork's origin and license are recorded next to the asset.
- `pnpm test` and `pnpm build` are green; if the frontend dependencies changed, the user's `nix build .` succeeds.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
