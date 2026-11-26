// A text's direction: the app language's (item 88) or a text's own (item 76).
export type TextDirection = 'ltr' | 'rtl';

// The letters of the right-to-left scripts: the code-point ranges Unicode gives the R and AL bidi
// classes (Hebrew and Yiddish, Arabic with Persian, Urdu, Sindhi and Pashto, Syriac, Thaana, N'Ko
// and the rest). Checked against Chromium 156's own resolution over every Unicode letter.
const RTL_LETTER =
  /[\u0590-\u08FF\uFB1D-\uFDFF\uFE70-\uFEFF\u{10800}-\u{10FFF}\u{1E800}-\u{1EFFF}]/u;

// Item 76's rule: a text's direction is its first letter's — rtl for a letter of a right-to-left
// script, ltr for any other; digits, punctuation and spaces before it do not decide. A text
// without a letter has no direction of its own (undefined).
export function letterDirection(text: string): TextDirection | undefined {
  const letter = text.match(/\p{L}/u)?.[0];
  if (letter === undefined) {
    return undefined;
  }
  return RTL_LETTER.test(letter) ? 'rtl' : 'ltr';
}

// Item 76's rule for a standalone text — a speaker name or a filename, on its own or inside a
// sentence: its letter direction, and ltr when it has no letter (not the app language's).
export function standaloneDirection(text: string): TextDirection {
  return letterDirection(text) ?? 'ltr';
}

// Item 76's rule for a transcript's turns, given each turn's text in order: a turn takes its own
// letter direction; a turn without a letter takes the previous lettered turn's, or at the start
// of the transcript the next one's; undefined when no turn has a letter.
export function turnDirections(texts: readonly string[]): (TextDirection | undefined)[] {
  const own = texts.map(letterDirection);
  return own.map(
    (direction, index) =>
      direction ??
      own.slice(0, index).findLast((other) => other !== undefined) ??
      own.slice(index + 1).find((other) => other !== undefined),
  );
}
