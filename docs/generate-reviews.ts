// Writes the translation review pages (roadmap item 88) from each locale's record: the catalog
// gives the translation, `en.json` the source text, the glossary the terms, and the page itself
// the comparisons and audits it found. Run it after changing a catalog, a glossary or an
// evidence cell; `docs/check-reviews.ts` fails when a committed page is not what it writes.
//
//   node docs/generate-reviews.ts
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { PAGES, SOURCE, catalogs, renderPage } from './review-page.ts';

const shipped = catalogs();
const source = shipped.get(SOURCE) ?? {};
let written = 0;

for (const [code, messages] of shipped) {
  if (code === SOURCE) continue;
  const path = join(PAGES, `${code}.md`);
  const markdown = readFileSync(path, 'utf8');
  const { text, problems } = renderPage(code, markdown, messages, source);
  if (problems.length > 0) {
    for (const problem of problems) console.log(`${path}: ${problem}`);
    process.exit(1);
  }
  if (text === markdown) continue;
  writeFileSync(path, text);
  written += 1;
}

console.log(`docs/reviews: ${written} page${written === 1 ? '' : 's'} written`);
