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
