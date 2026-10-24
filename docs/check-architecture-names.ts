// Checks that every name the architecture overview writes as code exists in the code
// (roadmap item 35): each `inline code` span outside fenced blocks is classified — a repo
// path, an endpoint, a frontend route, an event, a table, a package, a constant, a
// configuration variable, a function, a command, a path in the data directory — and
// verified against the source tree. A span no rule recognizes fails too, so nothing slips
// through unchecked. Exits 1 on any failure.
//
//   node docs/check-architecture-names.ts [docs/architecture.md]
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { basename, join } from 'node:path';
import { proseLines } from './markdown-lines.ts';

const STT = 'backend/src/main/kotlin/stt';
const MIGRATIONS = 'backend/src/main/resources/db/migration';

const cache = new Map<string, string>();
function readCached(path: string): string {
  if (!cache.has(path)) {
    cache.set(path, existsSync(path) ? readFileSync(path, 'utf8') : '');
  }
  return cache.get(path) ?? '';
}

function filesUnder(dir: string, extensions: string[]): string[] {
  if (!existsSync(dir)) {
    return [];
  }
  return readdirSync(dir, { recursive: true, encoding: 'utf8' })
    .map((name) => join(dir, name))
    .filter((path) => statSync(path).isFile() && extensions.some((ext) => path.endsWith(ext)));
}

const kotlinFiles = filesUnder(STT, ['.kt']);
const sourceFiles = [
  ...kotlinFiles,
  ...filesUnder('backend/src/main/resources', ['.sql']),
  ...filesUnder('frontend/src', ['.ts', '.tsx']),
  ...filesUnder('e2e', ['.ts']).filter((path) => !path.includes('node_modules')),
];

// Kotlin source without comment lines: a name must be used, not only mentioned.
const kotlinCode = kotlinFiles
  .map((path) =>
    readCached(path)
      .split('\n')
      .filter((line) => !/^\s*(\/\/|\/\*|\*)/.test(line))
      .join('\n'),
  )
  .join('\n');

// Every route: the backend declares each one with its full literal path.
const routes = new Map<string, string>();
for (const path of kotlinFiles.filter((file) => file.endsWith('Routes.kt'))) {
  for (const match of readCached(path).matchAll(/\b(get|post|put|delete)\("(\/api[^"]*)"\)/g)) {
    routes.set(`${(match[1] ?? '').toUpperCase()} ${match[2] ?? ''}`, path);
  }
}

// The tables that exist after the last migration.
const tables = new Set<string>();
const migrations = filesUnder(MIGRATIONS, ['.sql']).sort(
  (a, b) => Number(/V(\d+)__/.exec(a)?.[1]) - Number(/V(\d+)__/.exec(b)?.[1]),
);
for (const path of migrations) {
  const sql = readCached(path);
  for (const [, created, dropped, renamedFrom, renamedTo] of sql.matchAll(
    /CREATE TABLE (\w+)|DROP TABLE (?:IF EXISTS )?(\w+)|ALTER TABLE (\w+) RENAME TO (\w+)/gi,
  )) {
    if (created !== undefined) tables.add(created);
    if (dropped !== undefined) tables.delete(dropped);
    if (renamedFrom !== undefined && renamedTo !== undefined) {
      tables.delete(renamedFrom);
      tables.add(renamedTo);
    }
  }
}

const events = new Set(
  [...readCached(`${STT}/http/EventsRoutes.kt`).matchAll(/"([^"\n]*)"/g)].map(
    (match) => match[1] ?? '',
  ),
);
const dataDirLiterals = [`${STT}/db/Database.kt`, `${STT}/db/ScratchDir.kt`].flatMap((path) =>
  [...readCached(path).matchAll(/"([^"\n]*)"/g)].map((match) => match[1] ?? ''),
);

// Commands the overview names, each with what makes it real.
const commands: Record<string, () => boolean> = {
  'devenv up': () => /\bprocesses\s*=/.test(readCached('devenv.nix')),
  'nix build .': () => /\bpackages\b/.test(readCached('flake.nix')),
  'nix run .': () => /\bapps\b/.test(readCached('flake.nix')),
  'stt-app': () => readCached('flake.nix').includes('stt-app'),
  'gradle check': () => readCached('backend/build.gradle.kts').includes('ktlint'),
  'gradle integrationTest': () =>
    readCached('backend/build.gradle.kts').includes('integrationTest'),
  'pnpm test': () => {
    const pkg = JSON.parse(readCached('frontend/package.json')) as {
      scripts?: Record<string, unknown>;
    };
    return pkg.scripts?.['test'] !== undefined;
  },
};

interface Rule {
  kind: string;
  applies: (span: string) => boolean;
  holds: (span: string) => boolean;
}

// Each rule: does it apply to the span, and is the span real by its lights.
const rules: Rule[] = [
  {
    kind: 'endpoint',
    applies: (span) => /^(GET|POST|PUT|DELETE) \/api/.test(span),
    holds: (span) => {
      const [route = '', query] = span.replace(/\\\|/g, '|').split('?');
      const file = routes.get(route);
      return (
        file !== undefined &&
        (query === undefined ||
          query
            .split('&')
            .every((pair) =>
              readCached(file).includes(`queryParameters["${pair.split('=')[0] ?? ''}"]`),
            ))
      );
    },
  },
  {
    kind: 'API prefix',
    applies: (span) => /^\/api(\/|$)/.test(span),
    holds: (span) =>
      [...routes.keys()].some((route) => (route.split(' ')[1] ?? '').startsWith(span)),
  },
  {
    kind: 'frontend route',
    applies: (span) => /^\/[\w/:-]*$/.test(span) && !span.startsWith('/api'),
    holds: (span) => readCached('frontend/src/app/App.tsx').includes(`path="${span}"`),
  },
  {
    kind: 'command',
    applies: (span) => span in commands,
    holds: (span) => commands[span]?.() ?? false,
  },
  {
    kind: 'configuration variable',
    applies: (span) => /^STT_[A-Z_]+$/.test(span) || span === 'STT_*',
    holds: (span) =>
      readCached(`${STT}/config/Config.kt`).includes(span === 'STT_*' ? '"STT_' : `"${span}"`),
  },
  {
    kind: 'constant',
    applies: (span) => /^[A-Z][A-Z0-9_]+$/.test(span),
    holds: (span) => new RegExp(`\\b${span}\\b`).test(kotlinCode),
  },
  {
    kind: 'member',
    applies: (span) => /^[A-Z]\w*\.[a-z]\w*$/.test(span),
    holds: (span) => {
      const [type = '', member = ''] = span.split('.');
      const file = kotlinFiles.find((path) => basename(path) === `${type}.kt`);
      return file !== undefined && new RegExp(`\\bfun ${member}\\(`).test(readCached(file));
    },
  },
  {
    kind: 'function',
    applies: (span) => /^[a-z]+[A-Z]\w*$/.test(span),
    holds: (span) => new RegExp(`\\bfun ${span}\\(`).test(kotlinCode),
  },
  {
    kind: 'event',
    applies: (span) => /^[a-z]+(-[a-z]+)*$/.test(span),
    holds: (span) => events.has(span),
  },
  {
    kind: 'table',
    applies: (span) => /^[a-z][a-z_]*$/.test(span),
    holds: (span) => tables.has(span),
  },
  {
    kind: 'package',
    applies: (span) => /^[a-z]+$/.test(span),
    holds: (span) => existsSync(`${STT}/${span}`) && statSync(`${STT}/${span}`).isDirectory(),
  },
  {
    kind: 'file extension',
    applies: (span) => /^\.[a-z0-9]+$/.test(span),
    holds: (span) => new RegExp(`"${span.slice(1)}"`).test(kotlinCode),
  },
  {
    kind: 'error code',
    applies: (span) => /^\d{3} [a-z-]+$/.test(span),
    holds: (span) => kotlinCode.includes(`"${span.split(' ')[1] ?? ''}"`),
  },
  {
    kind: 'repo path',
    applies: (span) => /^[\w.-]+(\/[\w.-]*)*$/.test(span) && /[/.]/.test(span),
    holds: (span) =>
      existsSync(span) ||
      existsSync(`${STT}/${span}`) ||
      (!span.includes('/') && sourceFiles.some((path) => basename(path) === span)),
  },
  {
    kind: 'data-directory path',
    applies: (span) => /^[\w.<>/-]+$/.test(span),
    holds: (span) =>
      span
        .split(/<[^>]+>|\//)
        .filter((piece) => piece !== '')
        .every((piece) => dataDirLiterals.some((literal) => literal.includes(piece))),
  },
];

function codeSpans(markdown: string): Map<string, number> {
  const spans = new Map<string, number>();
  for (const { number, line } of proseLines(markdown)) {
    for (const match of line.matchAll(/`([^`]+)`/g)) {
      const span = match[1] ?? '';
      if (!spans.has(span)) {
        spans.set(span, number);
      }
    }
  }
  return spans;
}

const file = process.argv[2] ?? 'docs/architecture.md';
const failures: string[] = [];
const spans = codeSpans(readCached(file));
for (const [span, line] of spans) {
  const applicable = rules.filter((rule) => rule.applies(span));
  if (applicable.length === 0) {
    failures.push(`${file}:${line}: \`${span}\` — not a name any rule knows`);
  } else if (!applicable.some((rule) => rule.holds(span))) {
    const kinds = applicable.map((rule) => rule.kind).join(' / ');
    failures.push(`${file}:${line}: \`${span}\` — no such ${kinds} in the code`);
  }
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`All ${spans.size} code names in ${file} exist in the code.`);
