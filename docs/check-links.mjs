// Checks that every relative link and image in the given Markdown files resolves (roadmap
// item 35): the target file exists, relative to the linking file, and a #fragment into a
// Markdown file names one of its headings (GitHub's anchor rules). External URLs, mailto:
// and in-page anchors of non-Markdown targets are not checked. Exits 1 on any broken link.
//
//   node docs/check-links.mjs README.md docs/architecture.md
import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { proseLines } from './markdown-lines.mjs';

// GitHub's heading anchor: lower case, punctuation other than hyphens and spaces dropped,
// spaces become hyphens; repeated headings get -1, -2, … suffixes.
function anchorsOf(markdown) {
  const anchors = new Set();
  const seen = new Map();
  for (const { line } of proseLines(markdown)) {
    const heading = /^#{1,6}\s+(.*?)\s*#*\s*$/.exec(line);
    if (heading === null) {
      continue;
    }
    const base = heading[1]
      .toLowerCase()
      .replace(/[^\p{L}\p{N}\s_-]/gu, '')
      .replace(/\s/g, '-');
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    anchors.add(count === 0 ? base : `${base}-${count}`);
  }
  return anchors;
}

// Link targets outside fenced code blocks and inline code: Markdown links and images, and
// the src of HTML images.
function targetsOf(markdown) {
  const targets = [];
  for (const { number, line } of proseLines(markdown)) {
    const text = line.replace(/`[^`]*`/g, '');
    for (const match of text.matchAll(/!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g)) {
      targets.push({ line: number, target: match[1] });
    }
    for (const match of text.matchAll(/<img\b[^>]*\bsrc="([^"]+)"/g)) {
      targets.push({ line: number, target: match[1] });
    }
  }
  return targets;
}

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('usage: node docs/check-links.mjs <file.md>…');
  process.exit(2);
}

const broken = [];
for (const file of files) {
  if (!existsSync(file)) {
    broken.push(`${file}: file does not exist`);
    continue;
  }
  const markdown = readFileSync(file, 'utf8');
  for (const { line, target } of targetsOf(markdown)) {
    if (/^[a-z][a-z0-9+.-]*:/i.test(target)) {
      continue; // http:, https:, mailto: …
    }
    const [path, fragment] = target.split('#');
    const resolved = path === '' ? resolve(file) : resolve(dirname(file), decodeURI(path));
    if (!existsSync(resolved)) {
      broken.push(`${file}:${line}: ${target} — no such file`);
      continue;
    }
    if (fragment !== undefined && resolved.endsWith('.md') && statSync(resolved).isFile()) {
      if (!anchorsOf(readFileSync(resolved, 'utf8')).has(fragment)) {
        broken.push(`${file}:${line}: ${target} — no heading #${fragment}`);
      }
    }
  }
}

if (broken.length > 0) {
  console.error(broken.join('\n'));
  process.exit(1);
}
console.log(`All relative links resolve in ${files.join(', ')}.`);
