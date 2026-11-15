// Checks that every shipped catalog has a review page and that each page's table still agrees
// with its catalog (roadmap item 88, test-list line "review page"): every key is present once,
// no key is unknown, and the translation column equals the catalog's value. Exits 1 on any
// mismatch, naming the page, its line and the key.
//
//   node docs/check-reviews.ts
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { PAGES, SOURCE, catalogs, glossaryTerms, renderPage } from './review-page.ts';

interface Row {
  line: number;
  key: string;
  source: string;
  translation: string;
  compared: string;
  glossary: string;
}

// The review table's data rows: five cells between the leading and trailing pipe; the header and
// its separator carry no catalog key.
function rowsOf(markdown: string): Row[] {
  const rows: Row[] = [];
  for (const [index, line] of markdown.split('\n').entries()) {
    if (!line.startsWith('| ')) {
      continue;
    }
    const cells = line
      .split('|')
      .slice(1, -1)
      .map((cell) => cell.trim());
    if (cells.length !== 6 || !/^[a-z][A-Za-z0-9]*(\.[A-Za-z0-9-]+)*$/.test(cells[0] ?? '')) {
      continue;
    }
    rows.push({
      line: index + 1,
      key: cells[0] ?? '',
      source: cells[1] ?? '',
      translation: cells[2] ?? '',
      compared: cells[3] ?? '',
      glossary: cells[5] ?? '',
    });
  }
  return rows;
}

const shipped = catalogs();
const source = shipped.get(SOURCE) ?? {};
const pages = new Set(
  readdirSync(PAGES)
    .filter((file) => file.endsWith('.md'))
    .map((file) => file.replace(/\.md$/, '')),
);

const problems: string[] = [];
for (const [code, messages] of shipped) {
  if (code === SOURCE) {
    continue;
  }
  const page = join(PAGES, `${code}.md`);
  if (!pages.has(code)) {
    problems.push(`${page}: missing for the shipped catalog "${code}"`);
    continue;
  }
  const rows = rowsOf(readFileSync(page, 'utf8'));
  const seen = new Map<string, Row>();
  for (const row of rows) {
    if (seen.has(row.key)) {
      problems.push(`${page}:${row.line}: "${row.key}" appears twice`);
    }
    seen.set(row.key, row);
  }
  for (const [key, value] of Object.entries(messages)) {
    const row = seen.get(key);
    if (row === undefined) {
      problems.push(`${page}: missing row for "${key}"`);
    } else if (row.translation !== value) {
      problems.push(
        `${page}:${row.line}: "${key}" says "${row.translation}", the catalog says "${value}"`,
      );
    }
    if (row !== undefined) {
      const terms = glossaryTerms(code, value);
      if (row.glossary !== terms) {
        problems.push(
          `${page}:${row.line}: "${key}" has glossary "${row.glossary}", the glossary says "${terms}"`,
        );
      }
    }
    if (row !== undefined && row.source !== (source[key] ?? '')) {
      problems.push(
        `${page}:${row.line}: "${key}" has source "${row.source}", the English catalog says "${source[key] ?? ''}"`,
      );
    }
  }
  for (const row of rows) {
    if (!(row.key in messages)) {
      problems.push(`${page}:${row.line}: "${row.key}" is not in the catalog`);
    }
    // A comparison is the product and the wording it uses; a product name with only a rank
    // records no evidence (the review standard of 2026-11-12). A dash means none was found.
    if (row.compared !== '—' && !/[„«"「『“]/.test(row.compared)) {
      problems.push(`${page}:${row.line}: "${row.key}" names a product without its wording`);
    }
  }
}

for (const page of pages) {
  if (!shipped.has(page)) {
    problems.push(`${join(PAGES, `${page}.md`)}: no shipped catalog "${page}"`);
  }
}

// The page is a rendered artifact (proposal step 8): compare it with what the generator would
// write, so a hand edit the derived columns cannot fault — order, padding — still fails.
for (const [code, messages] of shipped) {
  if (code === SOURCE) continue;
  const path = join(PAGES, `${code}.md`);
  const markdown = readFileSync(path, 'utf8');
  const rendered = renderPage(code, markdown, messages, source);
  for (const problem of rendered.problems) problems.push(`${path}: ${problem}`);
  if (rendered.problems.length === 0 && rendered.text !== markdown) {
    problems.push(`${path}: not its generated form (run node docs/generate-reviews.ts)`);
  }
}

if (problems.length > 0) {
  for (const problem of problems) {
    console.log(problem);
  }
  process.exit(1);
}

console.log(`docs/reviews: ${shipped.size - 1} pages match their catalogs`);
