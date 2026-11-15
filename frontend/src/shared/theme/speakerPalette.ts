// The speaker palette (item 15) lives in the shared colour tokens (ADR 0068): one tint per
// speaker index, in hue order chosen by farthest-point selection in OKLab (min pairwise ΔE 7.8,
// consecutive 16.5; numbers in the item 24 roadmap's approval log). A tint is read as the
// speaker name's text color, so every entry sits at the AA boundary of its theme (4.60-7.07
// against background, surface and surfaceRaised). Both frontends take it from
// ui-theme/colors.json, so they cannot drift apart.

// A speaker's tint: the palette entry for its 0-based speaker index (item 12's speakerId),
// wrapping when a task has more speakers than the palette has entries.
export function speakerTint(tints: readonly string[], index: number): string {
  const tint = tints[index % tints.length];
  if (tint === undefined) {
    throw new Error('The speaker palette is empty');
  }
  return tint;
}
