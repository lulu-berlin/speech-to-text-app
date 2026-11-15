// The message catalog check (roadmap item 88): a user-visible string must come from the
// catalog, not from a literal in a component. The check scans a source file for JSX text
// and for the string values of the attributes a reader sees. Code strings (message ids,
// event types, class names), function bodies and non-visible attributes are not UI copy.
import languages from '../../../../docs/api/languages.json';
import uiLocales from '../../../../docs/api/ui-locales.json';

export interface LiteralViolation {
  file: string;
  line: number;
  text: string;
}

// The never-translated strings (roadmap item 88): the product name, file extensions, model
// names, whisper language codes and locale native names. A literal matching one is data the
// UI shows as it is, not copy to translate; the codes and names come from the contract data.
const NEVER_TRANSLATED: Record<string, true> = {};
for (const text of ['Speech-to-Text', 'whisper', 'whisper-cli', 'CAM++']) {
  NEVER_TRANSLATED[text] = true;
}
for (const language of languages.languages) {
  NEVER_TRANSLATED[language.code] = true;
}
for (const locale of uiLocales.locales) {
  NEVER_TRANSLATED[locale.nativeName] = true;
}

// The JSX attributes whose string value a reader sees.
const USER_VISIBLE_ATTRIBUTES =
  'aria-label|title|placeholder|label|alt|detail|confirmLabel|confirmTitle';

const ATTRIBUTE = new RegExp(
  `\\b(${USER_VISIBLE_ATTRIBUTES})\\s*=\\s*(?:"([^"]*)"|'([^']*)')`,
  'gu',
);

interface Tag {
  /** The index just past the tag's closing `>`. */
  end: number;
  selfClosing: boolean;
}

// Replace comments with spaces (keeping newlines, so every index and line stays the same):
// a `<button>` or an arrow inside a comment is not markup, and must not split a tag scan.
function stripComments(source: string): string {
  let result = '';
  let state: 'code' | 'line' | 'block' | 'single' | 'double' | 'template' = 'code';
  for (let i = 0; i < source.length; i += 1) {
    const char = source.charAt(i);
    const next = source.charAt(i + 1);
    if (state === 'code') {
      if (char === '/' && next === '/') {
        state = 'line';
        result += '  ';
        i += 1;
      } else if (char === '/' && next === '*') {
        state = 'block';
        result += '  ';
        i += 1;
      } else {
        if (char === "'") state = 'single';
        else if (char === '"') state = 'double';
        else if (char === '`') state = 'template';
        result += char;
      }
    } else if (state === 'line') {
      if (char === '\n') {
        state = 'code';
        result += '\n';
      } else {
        result += ' ';
      }
    } else if (state === 'block') {
      if (char === '*' && next === '/') {
        state = 'code';
        result += '  ';
        i += 1;
      } else {
        result += char === '\n' ? '\n' : ' ';
      }
    } else if (char === '\\') {
      result += char + next;
      i += 1;
    } else {
      if (
        (state === 'single' && char === "'") ||
        (state === 'double' && char === '"') ||
        (state === 'template' && char === '`')
      ) {
        state = 'code';
      }
      result += char;
    }
  }
  return result;
}

// A JSX opening or closing tag, with its end found at brace depth 0: a `>` inside a prop
// expression (an arrow, a comparison) does not close the tag. A `<` preceded by an
// identifier is a generic type (`Array<string>`), not a tag.
function findTags(source: string): Tag[] {
  const tags: Tag[] = [];
  for (let i = 0; i < source.length; i += 1) {
    if (source.charAt(i) !== '<' || /[A-Za-z0-9_$.]/.test(source.charAt(i - 1))) {
      continue;
    }
    const opening = /^<(\/?)([A-Za-z][\w.]*)/.exec(source.slice(i));
    if (opening === null) {
      continue;
    }
    let depth = 0;
    let end = -1;
    for (let j = i + opening[0].length; j < source.length; j += 1) {
      const char = source.charAt(j);
      if (char === '{') {
        depth += 1;
      } else if (char === '}') {
        depth = Math.max(0, depth - 1);
      } else if (char === '>' && depth === 0) {
        end = j + 1;
        break;
      } else if (char === '<' && depth === 0) {
        break;
      }
    }
    if (end === -1) {
      continue;
    }
    tags.push({ end, selfClosing: source.slice(i, end).trimEnd().endsWith('/>') });
  }
  return tags;
}

/** The user-visible literals [source] holds, in source order, keyed by [file]. */
export function findUserVisibleLiterals(source: string, file: string): LiteralViolation[] {
  const found: Array<LiteralViolation & { index: number }> = [];
  const report = (index: number, raw: string) => {
    const text = raw.trim().replace(/\s+/g, ' ');
    if (
      text.length < 2 ||
      !/\p{L}/u.test(text) ||
      NEVER_TRANSLATED[text] === true ||
      /^\.[a-z0-9]+$/i.test(text)
    ) {
      return;
    }
    found.push({ file, line: source.slice(0, index).split('\n').length, text, index });
  };

  const code = stripComments(source);
  for (const match of code.matchAll(ATTRIBUTE)) {
    const value = match[2] ?? match[3] ?? '';
    report((match.index ?? 0) + match[0].indexOf(value), value);
  }

  for (const tag of findTags(code)) {
    if (tag.selfClosing) {
      continue;
    }
    const end = code.indexOf('<', tag.end);
    if (end === -1) {
      continue;
    }
    const raw = code.slice(tag.end, end);
    // An expression splits the text; the plain parts around it are not scanned here, and
    // the code between two JSX expressions in a ternary is not text either.
    if (
      raw.includes('{') ||
      raw.includes('}') ||
      /\)\s*:|\?\s*\(|!==|===|=>|&&|\|\||;\s*(return|const|let|var|if|for|while)\b/.test(raw)
    ) {
      continue;
    }
    const first = raw.search(/\S/);
    if (first !== -1) {
      report(tag.end + first, raw);
    }
  }

  return found
    .sort((a, b) => a.index - b.index)
    .map(({ file: name, line, text }) => ({ file: name, line, text }));
}
