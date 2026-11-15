// The translation review page (roadmap item 88): each locale's page is its record — the
// comparisons and audits it found — rendered over the catalog, the English catalog and the
// glossary. `docs/generate-reviews.ts` writes it; `docs/check-reviews.ts` fails when the
// committed page is not what the generator would write, so a page can never drift by hand.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

export const CATALOGS = 'frontend/src/locales';
export const GLOSSARY = 'docs/glossary';
export const PAGES = 'docs/reviews';
export const SOURCE = 'en';

export type Catalog = Record<string, string>;

export function readJson(path: string): Catalog {
  return JSON.parse(readFileSync(path, 'utf8')) as Catalog;
}

/** Every shipped catalog by locale code, the English one included. */
export function catalogs(): Map<string, Catalog> {
  const map = new Map<string, Catalog>();
  for (const file of readdirSync(CATALOGS)) {
    if (!file.endsWith('.json')) continue;
    map.set(file.replace(/\.json$/, ''), readJson(join(CATALOGS, file)));
  }
  return map;
}

// The glossary terms a string uses: every local term of the locale's glossary that appears in
// the translation, joined in glossary order. A page carries them so a reader sees the settled
// word beside the string.
export function glossaryTerms(code: string, translation: string): string {
  const terms: string[] = [];
  for (const line of readFileSync(join(GLOSSARY, `${code}.md`), 'utf8').split('\n')) {
    if (!line.startsWith('| ')) continue;
    const cells = cellsOf(line);
    if (cells.length < 3 || cells[0] === 'English' || /^[- ]+$/.test(cells[0] ?? '')) continue;
    for (const part of (cells[1] ?? '').split(' / ')) {
      const term = part.replace(/\s*\([^)]*\)/g, '').trim();
      if (term.length > 1 && translation.includes(term) && !terms.includes(term)) terms.push(term);
    }
  }
  return terms.length === 0 ? '—' : terms.join(', ');
}

export interface Evidence {
  key: string;
  compared: string;
  audit: string;
}

export interface ReviewPage {
  /** Everything before the table's header line, the blank line above it included. */
  prologue: string;
  /** The third column's title: the locale's name in the documentation's language. */
  language: string;
  /** The comparisons and audits, in the order the page records them. */
  evidence: Evidence[];
}

function cellsOf(line: string): string[] {
  return line
    .split('|')
    .slice(1, -1)
    .map((cell) => cell.trim());
}

/** The record a page carries: its prose, its language column and its per-string evidence. */
export function parsePage(markdown: string): ReviewPage {
  const lines = markdown.split('\n');
  const header = lines.findIndex((line) => line.startsWith('| Key'));
  if (header < 0) throw new Error('the page has no review table');
  const language = cellsOf(lines[header] ?? '')[2] ?? '';
  if (language === '') throw new Error('the review table has no language column');
  const evidence: Evidence[] = [];
  for (const line of lines.slice(header + 2)) {
    if (!line.startsWith('| ')) break;
    const cells = cellsOf(line);
    if (cells.length !== 6) continue;
    evidence.push({ key: cells[0] ?? '', compared: cells[3] ?? '', audit: cells[4] ?? '' });
  }
  return { prologue: `${lines.slice(0, header).join('\n')}\n`, language, evidence };
}

export interface RenderedRow {
  key: string;
  source: string;
  translation: string;
  compared: string;
  audit: string;
  glossary: string;
}

// The columns are padded for a monospace reader, where a CJK character takes two columns and a
// combining mark or a zero-width joiner none: `String.length` would misalign Japanese and
// Chinese pages.
function codeWidth(codePoint: number): number {
  if (
    (codePoint >= 0x0300 && codePoint <= 0x036f) ||
    (codePoint >= 0x200b && codePoint <= 0x200f) ||
    codePoint === 0x200d ||
    (codePoint >= 0xfe00 && codePoint <= 0xfe0f)
  ) {
    return 0;
  }
  if (
    (codePoint >= 0x1100 && codePoint <= 0x115f) ||
    (codePoint >= 0x2e80 && codePoint <= 0x303e) ||
    (codePoint >= 0x3041 && codePoint <= 0x33ff) ||
    (codePoint >= 0x3400 && codePoint <= 0x4dbf) ||
    (codePoint >= 0x4e00 && codePoint <= 0x9fff) ||
    (codePoint >= 0xa000 && codePoint <= 0xa4cf) ||
    (codePoint >= 0xa960 && codePoint <= 0xa97f) ||
    (codePoint >= 0xac00 && codePoint <= 0xd7a3) ||
    (codePoint >= 0xf900 && codePoint <= 0xfaff) ||
    (codePoint >= 0xfe10 && codePoint <= 0xfe19) ||
    (codePoint >= 0xfe30 && codePoint <= 0xfe6f) ||
    (codePoint >= 0xff00 && codePoint <= 0xff60) ||
    (codePoint >= 0xffe0 && codePoint <= 0xffe6) ||
    (codePoint >= 0x1f300 && codePoint <= 0x1f64f) ||
    (codePoint >= 0x1f900 && codePoint <= 0x1f9ff) ||
    (codePoint >= 0x20000 && codePoint <= 0x3fffd)
  ) {
    return 2;
  }
  return 1;
}

function displayWidth(text: string): number {
  let width = 0;
  for (const character of text) width += codeWidth(character.codePointAt(0) ?? 0);
  return width;
}

function padTo(text: string, width: number): string {
  return text + ' '.repeat(Math.max(0, width - displayWidth(text)));
}

function table(language: string, rows: RenderedRow[]): string {
  const header = ['Key', 'English', language, 'Compared', 'Audit', 'Glossary'];
  const body = rows.map((row) => [
    row.key,
    row.source,
    row.translation,
    row.compared,
    row.audit,
    row.glossary,
  ]);
  const widths = header.map((cell, column) =>
    Math.max(displayWidth(cell), ...body.map((row) => displayWidth(row[column] ?? ''))),
  );
  const line = (cells: string[]): string =>
    `| ${cells.map((cell, column) => padTo(cell, widths[column] ?? 0)).join(' | ')} |`;
  return [line(header), line(widths.map((width) => '-'.repeat(width))), ...body.map(line)].join(
    '\n',
  );
}

/** The page as the generator writes it, and what keeps it from being written. */
export function renderPage(
  code: string,
  markdown: string,
  messages: Catalog,
  source: Catalog,
): { text: string; problems: string[] } {
  const page = parsePage(markdown);
  const problems: string[] = [];
  const byKey = new Map<string, Evidence>();
  for (const evidence of page.evidence) {
    if (byKey.has(evidence.key)) problems.push(`"${evidence.key}" appears twice`);
    byKey.set(evidence.key, evidence);
  }
  const rows: RenderedRow[] = [];
  for (const [key, english] of Object.entries(source)) {
    const translation = messages[key];
    const evidence = byKey.get(key);
    if (translation === undefined) {
      problems.push(`"${key}" is missing from the catalog`);
      continue;
    }
    if (evidence === undefined) {
      problems.push(`"${key}" has no recorded comparison`);
      continue;
    }
    rows.push({
      key,
      source: english,
      translation,
      compared: evidence.compared,
      audit: evidence.audit,
      glossary: glossaryTerms(code, translation),
    });
  }
  for (const evidence of page.evidence) {
    if (evidence.key in source) continue;
    problems.push(`"${evidence.key}" is not in the catalog`);
  }
  return { text: `${page.prologue}${table(page.language, rows)}\n`, problems };
}
