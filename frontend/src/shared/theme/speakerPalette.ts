// The speaker palette (item 15): one tint per speaker index, in hue order chosen by
// farthest-point selection in OKLab (min pairwise ΔE 7.8, consecutive 16.5; numbers in the
// item 24 roadmap's approval log). A tint is read as the speaker name's text color, so
// every entry sits at the AA boundary of its theme (4.60–7.07 against background, surface
// and surfaceRaised).
export const lightSpeakerTints: readonly string[] = [
  '#0d559c',
  '#df1212',
  '#0b820b',
  '#b912e3',
  '#6e4f09',
  '#952574',
  '#0a7d7d',
  '#6858f1',
  '#d51186',
  '#18603c',
  '#7411d6',
  '#7d710a',
  '#a51d0d',
  '#0f75b8',
  '#a85c2a',
  '#2135ed',
  '#085d67',
  '#4b5b07',
  '#9b48d1',
  '#cc3562',
];

// Item 59 lifted the dark raised surface to #32373f; the tints below it were re-derived
// (mixed minimally toward white) to keep every speaker name at AA text contrast there.
export const darkSpeakerTints: readonly string[] = [
  '#79b6f4',
  '#f57d7d',
  '#3bb73b',
  '#db79f4',
  '#e6a613',
  '#e699cf',
  '#39b0b0',
  '#9f95f7',
  '#f473c0',
  '#32ca7e',
  '#cb9ff7',
  '#aea234',
  '#f6998e',
  '#40a8ee',
  '#d79060',
  '#a3acf8',
  '#11c3d7',
  '#9bbf0f',
  '#c08ce2',
  '#e186a1',
];

// A speaker's tint: the palette entry for its 0-based speaker index (item 12's speakerId),
// wrapping when a task has more speakers than the palette has entries.
export function speakerTint(tints: readonly string[], index: number): string {
  const tint = tints[index % tints.length];
  if (tint === undefined) {
    throw new Error('The speaker palette is empty');
  }
  return tint;
}
