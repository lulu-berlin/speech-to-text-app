/** One way a locale's access keys break the editor's shortcuts. */
export interface AccessKeyViolation {
  locale: string;
  key: string;
  rule: 'not-one-letter' | 'collision' | 'reserved';
  detail?: string;
}

/** The access keys of one dialog, checked together: the segment editor's buttons. */
export const EDITOR_ACCESS_KEYS = ['transcript.editor.save', 'transcript.editor.discard'];

/**
 * Letters an access key may not use (item 88): with Ctrl/Cmd the text field already owns A, C,
 * V, X, Y and Z (select all, copy, paste, cut, redo, undo), and the browser keeps N, Q, T and W
 * (new window, quit, new tab, close tab) without handing them to the page.
 */
export const RESERVED_ACCESS_KEYS = new Set(['A', 'C', 'V', 'X', 'Y', 'Z', 'N', 'Q', 'T', 'W']);

/**
 * The letter a catalog message underlines with `<u>…</u>` (item 88), upper-cased: the
 * shortcut the editor listens for. Null unless the message underlines exactly one letter.
 */
export function accessKeyOf(message: unknown): string | null {
  if (typeof message !== 'string') {
    return null;
  }
  const underlines = [...message.matchAll(/<u>(.*?)<\/u>/gu)];
  const inner = underlines.length === 1 ? (underlines[0]?.[1] ?? '') : '';
  return [...inner].length === 1 ? inner.toLocaleUpperCase() : null;
}

/**
 * The access key check (roadmap item 88): every message of the group underlines exactly one
 * letter, no two messages share a letter, and no letter is reserved. A shared letter is
 * reported on the later message of the group.
 */
export function checkAccessKeys(
  locale: string,
  messages: Record<string, string>,
  group: readonly string[] = EDITOR_ACCESS_KEYS,
): AccessKeyViolation[] {
  const violations: AccessKeyViolation[] = [];
  const taken = new Set<string>();
  for (const key of group) {
    const letter = accessKeyOf(messages[key]);
    if (letter === null) {
      violations.push({ locale, key, rule: 'not-one-letter' });
      continue;
    }
    if (RESERVED_ACCESS_KEYS.has(letter)) {
      violations.push({ locale, key, rule: 'reserved', detail: letter });
    } else if (taken.has(letter)) {
      violations.push({ locale, key, rule: 'collision', detail: letter });
    }
    taken.add(letter);
  }
  return violations;
}
