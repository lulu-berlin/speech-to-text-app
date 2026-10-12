// Checks that every name the architecture overview writes as code exists in the code
// (roadmap item 35): each `inline code` span outside fenced blocks is classified — a repo
// path, an endpoint, a frontend route, an event, a table, a package, a constant, a
// configuration variable, a function, a command, a path in the data directory — and
// verified against the source tree. A span no rule recognizes fails too, so nothing slips
// through unchecked. Exits 1 on any failure.
//
//   node docs/check-architecture-names.mjs [docs/architecture.md]
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { basename, join } from 'node:path';
import { proseLines } from './markdown-lines.mjs';

const STT = 'backend/src/main/kotlin/stt';
const MIGRATIONS = 'backend/src/main/resources/db/migration';

const cache = new Map();
function read(path) {
  if (!cache.has(path)) {
    cache.set(path, existsSync(path) ? readFileSync(path, 'utf8') : '');
  }
  return cache.get(path);
}

function filesUnder(dir, extensions) {
  if (!existsSync(dir)) {
    return [];
  }
  return readdirSync(dir, { recursive: true })
    .map((name) => join(dir, name))
    .filter((path) => statSync(path).isFile() && extensions.some((ext) => path.endsWith(ext)));
}

const kotlinFiles = filesUnder(STT, ['.kt']);
const sourceFiles = [
  ...kotlinFiles,
  ...filesUnder('backend/src/main/resources', ['.sql']),
  ...filesUnder('frontend/src', ['.ts', '.tsx']),
  ...filesUnder('e2e', ['.ts', '.mjs']).filter((path) => !path.includes('node_modules')),
];

// Kotlin source without comment lines: a name must be used, not only mentioned.
const kotlinCode = kotlinFiles
  .map((path) =>
    read(path)
      .split('\n')
      .filter((line) => !/^\s*(\/\/|\/\*|\*)/.test(line))
      .join('\n'),
  )
  .join('\n');

const stringLiterals = (paths) =>
  paths.flatMap((path) => [...read(path).matchAll(/"([^"\n]*)"/g)].map((match) => match[1]));

// Every route: the backend declares each one with its full literal path.
const routes = new Map();
for (const path of kotlinFiles.filter((file) => file.endsWith('Routes.kt'))) {
  for (const match of read(path).matchAll(/\b(get|post|put|delete)\("(\/api[^"]*)"\)/g)) {
    routes.set(`${match[1].toUpperCase()} ${match[2]}`, path);
  }
}

// The tables that exist after the last migration.
const tables = new Set();
const migrations = filesUnder(MIGRATIONS, ['.sql']).sort(
  (a, b) => Number(/V(\d+)__/.exec(a)?.[1]) - Number(/V(\d+)__/.exec(b)?.[1]),
);
for (const path of migrations) {
  const sql = read(path);
  for (const match of sql.matchAll(
    /CREATE TABLE (\w+)|DROP TABLE (?:IF EXISTS )?(\w+)|ALTER TABLE (\w+) RENAME TO (\w+)/gi,
  )) {
    if (match[1] !== undefined) tables.add(match[1]);
    if (match[2] !== undefined) tables.delete(match[2]);
    if (match[3] !== undefined) {
      tables.delete(match[3]);
      tables.add(match[4]);
    }
  }
}

const events = new Set(stringLiterals([`${STT}/http/EventsRoutes.kt`]));
const dataDirLiterals = stringLiterals([
  `${STT}/db/Database.kt`,
  `${STT}/db/TaskWorkDirectories.kt`,
  `${STT}/db/UploadSpool.kt`,
]);

// Commands the overview names, each with what makes it real.
const commands = {
  'devenv up': () => /\bprocesses\s*=/.test(read('devenv.nix')),
  'nix build .': () => /\bpackages\b/.test(read('flake.nix')),
  'nix run .': () => /\bapps\b/.test(read('flake.nix')),
  'stt-app': () => read('flake.nix').includes('stt-app'),
  'gradle check': () => read('backend/build.gradle.kts').includes('ktlint'),
  'gradle integrationTest': () => read('backend/build.gradle.kts').includes('integrationTest'),
  'pnpm test': () => JSON.parse(read('frontend/package.json')).scripts?.test !== undefined,
};

// Each rule: does it apply to the span, and is the span real by its lights.
const rules = [
  {
    kind: 'endpoint',
    applies: (span) => /^(GET|POST|PUT|DELETE) \/api/.test(span),
    holds: (span) => {
      const [route, query] = span.replace(/\\\|/g, '|').split('?');
      const file = routes.get(route);
      return (
        file !== undefined &&
        (query === undefined ||
          query
            .split('&')
            .every((pair) => read(file).includes(`queryParameters["${pair.split('=')[0]}"]`)))
      );
    },
  },
  {
    kind: 'API prefix',
    applies: (span) => /^\/api(\/|$)/.test(span),
    holds: (span) => [...routes.keys()].some((route) => route.split(' ')[1].startsWith(span)),
  },
  {
    kind: 'frontend route',
    applies: (span) => /^\/[\w/:-]*$/.test(span) && !span.startsWith('/api'),
    holds: (span) => read('frontend/src/app/App.tsx').includes(`path="${span}"`),
  },
  {
    kind: 'command',
    applies: (span) => span in commands,
    holds: (span) => commands[span](),
  },
  {
    kind: 'configuration variable',
    applies: (span) => /^STT_[A-Z_]+$/.test(span) || span === 'STT_*',
    holds: (span) =>
      read(`${STT}/config/Config.kt`).includes(span === 'STT_*' ? '"STT_' : `"${span}"`),
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
      const [type, member] = span.split('.');
      const file = kotlinFiles.find((path) => basename(path) === `${type}.kt`);
      return file !== undefined && new RegExp(`\\bfun ${member}\\(`).test(read(file));
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
    holds: (span) => kotlinCode.includes(`"${span.split(' ')[1]}"`),
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

function codeSpans(markdown) {
  const spans = new Map();
  for (const { number, line } of proseLines(markdown)) {
    for (const match of line.matchAll(/`([^`]+)`/g)) {
      if (!spans.has(match[1])) {
        spans.set(match[1], number);
      }
    }
  }
  return spans;
}

const file = process.argv[2] ?? 'docs/architecture.md';
const failures = [];
const spans = codeSpans(read(file));
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
