// Checks the exception-handling policy (roadmap item 60) in the backend's production Kotlin: every
// `catch` of `Exception` or `Throwable` must rethrow, hand the caught throwable to a logger, or carry
// `// exceptions: swallow-ok <reason>` in its body (a deliberate, reviewed degradation); `runCatching`
// is not allowed at all. Comments are ignored, line numbers kept. Exits 1 on any violation.
//
//   node docs/check-exceptions.ts [root]     (default: backend/src/main/kotlin)
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.argv[2] ?? 'backend/src/main/kotlin';
const MARKER = /\bexceptions:\s*swallow-ok\b/;
const LOG_CALL = /\b\w*(?:[Ll]ogger|[Ll]og)\s*\.\s*(?:trace|debug|info|warn|error)\s*\(/;

function kotlinFiles(dir: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      files.push(...kotlinFiles(path));
    } else if (entry.endsWith('.kt')) {
      files.push(path);
    }
  }
  return files.sort();
}

// The source with comments and string contents blanked (newlines kept, so offsets and line numbers
// still map); the marker is looked up in the original text instead.
function codeOnly(source: string): string {
  let out = '';
  let i = 0;
  while (i < source.length) {
    if (source.startsWith('//', i)) {
      const end = source.indexOf('\n', i);
      const stop = end === -1 ? source.length : end;
      out += ' '.repeat(stop - i);
      i = stop;
    } else if (source.startsWith('/*', i)) {
      const end = source.indexOf('*/', i + 2);
      const stop = end === -1 ? source.length : end + 2;
      out += source.slice(i, stop).replace(/[^\n]/g, ' ');
      i = stop;
    } else if (source[i] === '"') {
      out += '"';
      i += 1;
      while (i < source.length && source[i] !== '"') {
        if (source[i] === '\\') {
          out += '  ';
          i += 2;
        } else {
          out += source[i] === '\n' ? '\n' : ' ';
          i += 1;
        }
      }
      if (i < source.length) {
        out += '"';
        i += 1;
      }
    } else {
      out += source[i];
      i += 1;
    }
  }
  return out;
}

// The index just past the bracket paired with the one at [open].
function closingIndex(text: string, open: number): number {
  const opener = text[open];
  const closer = opener === '{' ? '}' : ')';
  let depth = 0;
  for (let i = open; i < text.length; i += 1) {
    if (text[i] === opener) {
      depth += 1;
    } else if (text[i] === closer) {
      depth -= 1;
      if (depth === 0) {
        return i + 1;
      }
    }
  }
  return text.length;
}

// The arguments of the call whose opening bracket is at [open], split on top-level commas. String
// contents are already blanked, so a comma inside one cannot split an argument.
function callArguments(body: string, open: number): string[] {
  const end = closingIndex(body, open);
  const args: string[] = [];
  let depth = 0;
  let start = open + 1;
  for (let i = open + 1; i < end - 1; i += 1) {
    const char = body[i];
    if (char === '(' || char === '[' || char === '{') depth += 1;
    else if (char === ')' || char === ']' || char === '}') depth -= 1;
    else if (char === ',' && depth === 0) {
      args.push(body.slice(start, i));
      start = i + 1;
    }
  }
  args.push(body.slice(start, end - 1));
  return args;
}

// Whether a logger call gets the caught throwable itself as an argument — not e.toString(), e.message
// or any other expression built from it, which loses its stack.
function logsThrowable(body: string, name: string): boolean {
  const call = new RegExp(LOG_CALL.source, 'g');
  for (const match of body.matchAll(call)) {
    const open = match.index + match[0].length - 1;
    if (callArguments(body, open).some((argument) => argument.trim() === name)) {
      return true;
    }
  }
  return false;
}

// Whether a throw in [body] preserves the caught [name]: as the thrown value (or part of it, e.g. a
// cause argument) or through addSuppressed. `throw IllegalStateException("x")` does not.
function rethrowsThrowable(body: string, name: string): boolean {
  const variable = new RegExp(`\\b${name}\\b`);
  for (const match of body.matchAll(/\bthrow\b/g)) {
    const start = match.index + match[0].length;
    let depth = 0;
    let end = start;
    for (let i = start; i < body.length; i += 1) {
      const char = body[i];
      if (char === '(' || char === '[' || char === '{') {
        depth += 1;
      } else if (char === ')' || char === ']' || char === '}') {
        if (depth === 0) break;
        depth -= 1;
      } else if (char === '\n' && depth === 0) {
        break;
      }
      end = i + 1;
    }
    if (variable.test(body.slice(start, end))) {
      return true;
    }
  }
  return new RegExp(`\\baddSuppressed\\s*\\([^)]*\\b${name}\\b`).test(body);
}

const violations: string[] = [];

for (const file of kotlinFiles(root)) {
  const source = readFileSync(file, 'utf8');
  const code = codeOnly(source);
  const shown = relative(process.cwd(), file);

  for (const match of code.matchAll(/\brunCatching\s*[({]/g)) {
    violations.push(
      `${shown}:${code.slice(0, match.index).split('\n').length}: runCatching is not allowed in production code — narrow to the expected exception`,
    );
  }

  const catchPattern = /\bcatch\s*\(\s*(\w+)\s*:\s*(Exception|Throwable)\s*\)\s*\{/g;
  for (const match of code.matchAll(catchPattern)) {
    const name = match[1];
    const open = match.index + match[0].length - 1;
    const end = closingIndex(code, open);
    const body = code.slice(open, end);
    const marker = MARKER.test(source.slice(match.index, end));
    const rethrows = rethrowsThrowable(body, name);
    if (!marker && !rethrows && !logsThrowable(body, name)) {
      violations.push(
        `${shown}:${code.slice(0, match.index).split('\n').length}: catch of ${match[2]} swallows its throwable — rethrow it, log it, or mark the catch // exceptions: swallow-ok <reason>`,
      );
    }
  }
}

if (violations.length > 0) {
  console.error(violations.join('\n'));
  process.exit(1);
}
console.log(`Every broad catch follows the exception policy and no runCatching remains in ${root}.`);