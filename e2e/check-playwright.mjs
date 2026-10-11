// Roadmap item 39: fail loudly when the e2e Playwright stack drifts, instead of
// Playwright's cryptic "Executable doesn't exist … chromium-<old>" and a hanging
// `npx playwright install`. The players must agree:
//
//   1. the version and browser revision the devenv.nix comment names,
//   2. e2e/package.json's @playwright/test requirement,
//   3. the installed @playwright/test in e2e/node_modules (a stale install drifts
//      from the requirement),
//   4. the chromium revision the installed playwright expects (its own
//      browsers.json) against the realized browser farm at $PLAYWRIGHT_BROWSERS_PATH.
//
// Run as part of `pnpm --dir e2e test` (before `playwright test`).
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');

const fail = (message) => {
  console.error(`FAILED: ${message}`);
  process.exit(1);
};

const ok = (message) => console.log(`ok: ${message}`);

// 1. What the comment names (devenv.nix's e2e comment keeps this parseable form).
const devenv = readFileSync(join(root, 'devenv.nix'), 'utf8');
const pin = devenv.match(/playwright (\d+\.\d+\.\d+), browser revisions? (\d+)/);
if (!pin) fail("devenv.nix's e2e comment must name 'playwright <version>, browser revisions <n>'");
const [commentedVersion, commentedRevision] = [pin[1], pin[2]];

// 2. What e2e/package.json requires.
const pkg = JSON.parse(readFileSync(join(here, 'package.json'), 'utf8'));
const required = pkg.devDependencies?.['@playwright/test'];
if (!required) fail('e2e/package.json has no @playwright/test devDependency');

// 3. What the store actually provides.
const store = process.env.PLAYWRIGHT_BROWSERS_PATH;
if (!store) fail('PLAYWRIGHT_BROWSERS_PATH is not set (devenv.nix sets it; re-enter the shell)');
let storeRevision;
try {
  const chromium = readdirSync(store).find((entry) => /^chromium-\d+$/.test(entry));
  storeRevision = chromium?.split('-')[1];
} catch (err) {
  fail(`store ${store} is not readable: ${err.message}`);
}

if (commentedVersion !== required) {
  fail(
    `devenv.nix comment names @playwright/test ${commentedVersion}, e2e/package.json requires ${required} — pnpm --dir e2e install`,
  );
}
if (commentedRevision !== storeRevision) {
  fail(
    `devenv.nix comment names browser revisions ${commentedRevision}, the store at ${store} provides chromium-${storeRevision}`,
  );
}

// 4. The installed package must be the required one (a stale install drifts), and
// the chromium revision it expects must be in the store. Resolve both through the
// e2e package's own dependencies, so an uninstalled or stale node_modules fails
// with the pnpm hint instead of Playwright's "Executable doesn't exist".
// @playwright/test is a transitive-dep consumer, so pnpm keeps playwright-core in
// the virtual store (node_modules/.pnpm/playwright-core@<version>/…) rather than
// hoisting it to the package root — locate it by the installed version.
const npmRoot = join(here, 'node_modules');
let installed, expectedRevision;
try {
  installed = JSON.parse(
    readFileSync(join(npmRoot, '@playwright/test/package.json'), 'utf8'),
  ).version;
  const coreDir = readdirSync(join(npmRoot, '.pnpm')).find(
    (entry) => entry === `playwright-core@${installed}`,
  );
  if (!coreDir) {
    fail(`playwright-core@${installed} is missing — pnpm --dir e2e install`);
  }
  const browsers = JSON.parse(
    readFileSync(
      join(npmRoot, '.pnpm', coreDir, 'node_modules', 'playwright-core', 'browsers.json'),
      'utf8',
    ),
  );
  expectedRevision = String(browsers.browsers.find((b) => b.name === 'chromium').revision);
} catch (err) {
  if (err.code === 'ENOENT') {
    fail('e2e dependencies are not installed — pnpm --dir e2e install');
  }
  throw err;
}

if (installed !== required) {
  fail(
    `installed @playwright/test is ${installed}, e2e/package.json requires ${required} — pnpm --dir e2e install`,
  );
}
if (expectedRevision !== storeRevision) {
  fail(
    `installed @playwright/test ${installed} expects chromium-${expectedRevision}, the store at ${store} provides chromium-${storeRevision} — pnpm --dir e2e install`,
  );
}

ok(
  `comment, package.json, install and store agree: @playwright/test ${installed}, chromium-${expectedRevision}`,
);
