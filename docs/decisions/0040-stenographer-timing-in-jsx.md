# 0040 — Stenographer animation timing lives on the elements (JSX `style`)

Status: accepted · Date: 2026-10-10

## Context
The frontend conventions ([docs/conventions/frontend.md](../conventions/frontend.md)) ban
inline `style={}` props so every design value comes from the tokens. Item 17's artwork
(`stenographer.svg`) carries its animation timing in CSS instead: the key-flash, word-rise,
wave and sparkle delays are `nth-child` rules, and the finger press timings are per-class
rules (`.arm-l .f1` … `.arm-r .thumb`). That couples the look to element order and spreads
each element's timing away from its geometry. The approved item 17 proposal (2026-10-09)
sets per-element `animation-delay` and `animation-duration` values in JSX rather than
recreating those rules. These values are motion offsets (negative delays start an element
mid-loop), not design values (colors, lengths), and the convention check
(`src/conventions.test.ts`) only enforces colors and lengths — so the inlined component
passes it either way.

## Decision
- `StenographerAnimation.tsx` may set exactly `animation-delay` and `animation-duration`
  via JSX `style` props on its animated SVG elements (waves, words, keys, fingers, thumbs,
  the right arm).
- This is a narrow exception to the no-inline-style convention: the two timing properties
  only, in this component only. Everything else — colors, sizes, durations of the whole
  loops — stays in `theme.ts` and the styled wrapper.

## Consequences
- The look no longer depends on element order: reordering the keys or words does not
  change which element flashes when.
- A reader sees each element's timing next to its geometry, and the styled wrapper holds
  only the animation rules themselves.
- Any future component with per-element animation timing follows the same narrow allowance
  and stays within the two properties; new design values still enter the tokens.