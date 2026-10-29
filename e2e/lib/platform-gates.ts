// Roadmap item 79, test-list item 4: the journey must not skip a spec silently. Every
// `test.skip`/`test.fixme` in a spec names why, so the run reports what did not execute and the
// roadmap's test list can name the gated specs from the same truth.
//
// The specs are read as text: they are Playwright files, not modules of this package, and the shape
// under test (a skip call and its reason) is syntactic.

export interface SpecSkip {
  spec: string;
  reason: string;
}

const SKIP_CALL = /\b(?:test|describe)\s*\.\s*(?:skip|fixme)\s*\(/g;

/**
 * Every skip and fixme in [specs], with the reason it names. Throws for a call that names none —
 * Playwright's skip/fixme take the reason as their string argument, which is what the run prints.
 */
export function specSkips(specs: readonly { name: string; source: string }[]): SpecSkip[] {
  const skips: SpecSkip[] = [];
  for (const { name, source } of specs) {
    for (const reason of skipReasons(source)) {
      if (reason === null) {
        throw new Error(`${name}: a skip without a reason skips silently — name why it cannot run`);
      }
      skips.push({ spec: name, reason });
    }
  }
  return skips;
}

/**
 * The reason of each skip/fixme call in [source], in order; null when the call names none. One pass
 * per call: the arguments are scanned once, tracking strings and nesting, and the last argument's
 * string literal is the reason Playwright prints.
 */
function skipReasons(source: string): (string | null)[] {
  const reasons: (string | null)[] = [];
  for (const match of source.matchAll(SKIP_CALL)) {
    let depth = 1;
    let quote: string | null = null;
    let argument = '';
    for (let index = (match.index ?? 0) + match[0].length; index < source.length; index++) {
      const char = source[index] ?? '';
      if (quote !== null) {
        argument += char;
        if (char === '\\') argument += source[++index] ?? '';
        else if (char === quote) quote = null;
        continue;
      }
      if (char === "'" || char === '"' || char === '`') {
        quote = char;
        argument += char;
      } else if (char === '(' || char === '[' || char === '{') {
        depth += 1;
        argument += char;
      } else if (char === ')' || char === ']' || char === '}') {
        depth -= 1;
        if (depth === 0) break;
        argument += char;
      } else if (char === ',' && depth === 1) {
        argument = '';
      } else {
        argument += char;
      }
    }
    const literal = /^(['"`])((?:\\.|(?!\1).)*)\1$/s.exec(argument.trim());
    reasons.push(literal?.[2] || null);
  }
  return reasons;
}
