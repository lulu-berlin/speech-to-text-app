# Brand assets — origin and licence

## `stenographer.svg`

- **What**: the app's mascot: an animated stenographer typing at a steno machine, drawn as a
  self-contained SVG (no external references, no scripts, no fonts). It carries its own CSS
  animations and a `prefers-reduced-motion` fallback.
- **Origin**: created by the project author (Lulu Hever) with Claude (Anthropic) on the Claude
  website, October 2026. It is AI-generated artwork; no third-party illustration, font or icon
  set was used or copied, so no third-party licence applies beyond the project's own.
- **Licence**: distributed with this project under the project's licence.
- **Used by**: roadmap item 17, which inlines it as a React component under
  `frontend/src/shared/brand/` and derives the favicon and a static still from it.
- **Checked** 2026-10-09: parses as XML; 138 elements; 13,786 bytes, 3,203 bytes gzipped
  (item 17's budget is 15 KB gzipped); no `<script>`, `<image>`, `<text>` or `href`.
- **Note for item 17**: the file's `<style>` block uses generic class names and a
  `* { animation: none !important; }` reduced-motion rule, and its 32 colors are literals.
  Both must be handled when it is inlined (see the item's Proposal).

## `favicon.svg`

- **What**: the app's browser favicon: the artwork's static frame — the same drawing with
  the `<style>` block, `role` and `aria-label` removed, so nothing animates and it carries
  no accessibility semantics.
- **Origin/derivation**: mechanically derived from `stenographer.svg` (item 17, 2026-10-10)
  by stripping the style block and the role/label attributes; same licence as the artwork.
- **Used by**: `frontend/index.html` links it as `/src/shared/brand/favicon.svg`; Vite
  bundles it into `dist/assets/` on build (the `publicDir: 'mock'` setup copies no public
  directory).

## `stenographer-still.svg`

- **What**: a static image of the mascot for documentation: the artwork's static frame —
  same drawing with the `<style>` block removed; the `role="img"`/`aria-label` are kept so
  documents can reference it accessibly.
- **Origin/derivation**: mechanically derived from `stenographer.svg` (item 17,
  2026-10-10) by stripping the style block; same licence as the artwork.
- **Used by**: documentation and release materials; not referenced by the app.
