// Roadmap item 84: the journey must run against the UI this checkout builds, so the build's
// inputs are digested and stored beside `dist`. A journey rebuilds when, and only when, that
// digest differs from the sources.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';

// Every build input outside `src` and `public`: the HTML entry, the Vite and TypeScript configs,
// the package manifest and lockfile. Anything else in `frontend` (mock/, README.md) cannot change
// the bundle, so it must not force a rebuild.
const CONFIG_INPUTS = [
  'index.html',
  'vite.config.ts',
  'tsconfig.json',
  'package.json',
  'pnpm-lock.yaml',
];

// A contract example the app imports (`resolveJsonModule`) is inlined into the bundle, so it is a
// build input too; an example nothing imports is not.
const IMPORTED_EXAMPLE = /docs\/api\/([A-Za-z0-9._-]+\.json)/g;

// The posix path of every file under [dir], relative to [root]; nothing when the directory is absent.
function filesUnder(root: string, dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return filesUnder(root, path);
    return [relative(root, path).split('\\').join('/')];
  });
}

function importedExamples(root: string, frontend: string): string[] {
  const names = new Set<string>();
  for (const file of filesUnder(root, join(frontend, 'src'))) {
    if (!/\.(ts|tsx)$/.test(file)) continue;
    const text = readFileSync(join(root, file), 'utf8');
    for (const match of text.matchAll(IMPORTED_EXAMPLE)) names.add(`docs/api/${match[1]}`);
  }
  return [...names];
}

// A frontend test file is not bundled, so editing one must not rebuild the UI (item 84's review).
const NOT_BUILD_INPUT = /\.test\.(ts|tsx)$/;

/** Every file that can change the built UI, as root-relative posix paths, sorted. */
export function buildInputs(root: string, frontend = join(root, 'frontend')): string[] {
  const configs = CONFIG_INPUTS.map((name) =>
    relative(root, join(frontend, name)).split('\\').join('/'),
  ).filter((path) => existsSync(join(root, path)));
  return [
    ...new Set([
      ...filesUnder(root, join(frontend, 'src')).filter((path) => !NOT_BUILD_INPUT.test(path)),
      ...filesUnder(root, join(frontend, 'public')),
      ...configs,
      ...importedExamples(root, frontend),
    ]),
  ].sort();
}

/** The digest of the build inputs: each file's path and bytes, hashed. */
export function uiDigest(root: string, frontend = join(root, 'frontend')): string {
  const hash = createHash('sha256');
  for (const path of buildInputs(root, frontend)) {
    hash
      .update(path)
      .update('\0')
      .update(readFileSync(join(root, path)))
      .update('\n');
  }
  return hash.digest('hex');
}

/** Where the digest of the `dist` beside [frontend] is stored: next to it, named for it. */
export function digestPath(frontend: string): string {
  return join(frontend, 'dist', '.ui-digest');
}

/** Whether `dist` holds a build of these inputs: no bundle, no stored digest, or a different one. */
export function uiIsStale(root: string, frontend = join(root, 'frontend')): boolean {
  const stored = digestPath(frontend);
  if (!existsSync(join(frontend, 'dist', 'index.html')) || !existsSync(stored)) return true;
  return readFileSync(stored, 'utf8').trim() !== uiDigest(root, frontend);
}

/** Records that `dist` now holds a build of these inputs. */
export function recordUiDigest(root: string, frontend = join(root, 'frontend')): void {
  writeFileSync(digestPath(frontend), `${uiDigest(root, frontend)}\n`);
}

/** What a journey did with the built UI: rebuilt it from the checkout, or found it current. */
export type UiBuild = 'rebuilt' | 'reused';

/**
 * Builds the UI when its inputs changed since the last build, and reuses `dist` when they did not:
 * the whole decision for both the Gradle path and a direct Playwright run. [build] does the actual
 * build; its digest is recorded only after it succeeded.
 */
export async function ensureUiBuilt({
  root,
  frontend = join(root, 'frontend'),
  build,
  log = () => {},
}: {
  root: string;
  frontend?: string;
  build: () => Promise<void>;
  log?: (line: string) => void;
}): Promise<UiBuild> {
  if (!uiIsStale(root, frontend)) {
    log('e2e: the built UI matches its sources');
    return 'reused';
  }
  await build();
  recordUiDigest(root, frontend);
  log('e2e: the UI was rebuilt from its sources');
  return 'rebuilt';
}
