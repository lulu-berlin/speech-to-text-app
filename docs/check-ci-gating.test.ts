// The CI gating check (roadmap item 80): a documentation-only pull request must skip every job
// that builds, tests or packages code, while the docs check and the required
// `CI (all checks)` still run. The workflow's own runs are the proof on GitHub; this test pins the
// shape locally and runs the classifier the `changes` job runs on real diffs. Run with
// `node --test docs/check-ci-gating.test.ts` from the repository root.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { assertJobOf, commandsOf, parseJobs, stepsOf, workflowPath } from './ci-workflow.ts';
import { repoRoot } from './run-check.ts';

const classifierPath = join(repoRoot, '.github/classify-change.sh');
const decidePath = join(repoRoot, '.github/decide-code-change.sh');

// Jobs that must run on every event. `changes` decides, `docs-check` is the cheap always-on
// check, and `ci` is the required aggregator that reports the run's result.
const UNGATED: Record<string, true> = { changes: true, 'docs-check': true, ci: true };
// Jobs that are gated by their own event instead: the manual assets (a dispatch with `assets`) and
// the weekly model-host probe (a cron string); none of them is in the aggregator. The Windows
// journey started here while it was nightly-only and left once its time matched macOS (roadmap
// item 79).
const EVENT_GATED: Record<string, true> = {
  'linux-whisper-asset': true,
  'macos-whisper-asset': true,
  'macos-ffmpeg-asset': true,
  'windows-whisper-asset': true,
  'windows-ffmpeg-asset': true,
  'model-hosts': true,
};
// The three journeys: the longest jobs of a run, and since roadmap item 85 the only heavy jobs that
// start with the change classification instead of waiting for their platform's checks.
const JOURNEYS = ['e2e', 'macos-e2e', 'windows-e2e'];

// The `code` value the `changes` job reads, from the classifier the job pipes its diff into.
function classify(paths: string[]): string {
  assert.ok(
    existsSync(classifierPath),
    '.github/classify-change.sh exists: the changes job classifies its diff instead of guessing',
  );
  return execFileSync('bash', [classifierPath], {
    input: `${paths.join('\n')}\n`,
    encoding: 'utf8',
  }).trim();
}

// A throwaway git repository: [commit] writes [files] (relative path -> content) on top of the
// previous commit and returns the new commit id, so a test can hand the decision script a real
// base/head pair.
function tempRepo(): {
  dir: string;
  commit(files: Record<string, string>, message: string): string;
} {
  const dir = mkdtempSync(join(tmpdir(), 'stt-ci-gating-'));
  const git = (...args: string[]): string =>
    execFileSync('git', args, { cwd: dir, encoding: 'utf8' }).trim();
  git('init', '-q');
  git('config', 'user.email', 'ci-gating@example.invalid');
  git('config', 'user.name', 'CI gating test');
  return {
    dir,
    commit(files: Record<string, string>, message: string): string {
      for (const [path, content] of Object.entries(files)) {
        mkdirSync(dirname(join(dir, path)), { recursive: true });
        writeFileSync(join(dir, path), content);
      }
      git('add', '-A');
      git('commit', '-qm', message);
      return git('rev-parse', 'HEAD');
    },
  };
}

// The `changes` job's decision for [event] on the base/head range, from .github/decide-code-change.sh
// — the script the workflow step pipes into $GITHUB_OUTPUT.
function decide(
  dir: string,
  event: string,
  range: { base?: string; head?: string } = {},
  forceFull = false,
): string {
  return execFileSync('bash', [decidePath], {
    cwd: dir,
    encoding: 'utf8',
    env: {
      ...process.env,
      EVENT: event,
      FORCE_FULL: String(forceFull),
      BASE_SHA: range.base ?? '',
      HEAD_SHA: range.head ?? '',
    },
  }).trim();
}

test('a documentation-only pull request skips every heavy job and still reports the required check', () => {
  assert.equal(
    classify([
      'docs/decisions/0050-pr-merge-on-github.md',
      'roadmap/80-docs-only-ci.md',
      'README.md',
      'LICENSE',
    ]),
    'code=false',
    'a diff of Markdown files and LICENSE is documentation only',
  );

  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));

  // The workflow decides code-vs-documentation once, in a changes job.
  const changes = assertJobOf(jobs, 'changes');
  assert.match(changes.raw.join('\n'), /fetch-depth: 0/, 'the changes job fetches the base commit');
  assert.match(
    changes.raw.join('\n'),
    /decide-code-change\.sh/,
    'the changes job decides from the real diff with .github/decide-code-change.sh',
  );

  // The docs job is the always-on cheap check; item 49 renamed its display name to the platform
  // prefix, so the job id is what the gate promises to leave alone.
  assert.equal(jobs.get('docs-check')?.needs.length, 0, 'docs-check waits for nothing');
  assert.equal(jobs.get('docs-check')?.if, null, 'docs-check runs even when no code changed');

  for (const job of jobs.values()) {
    if (UNGATED[job.id] === true || EVENT_GATED[job.id] === true) continue;
    assert.ok(
      job.needs.includes('changes'),
      `${job.id} needs changes, so a documentation-only pull request can skip it`,
    );
    assert.equal(
      job.if,
      "needs.changes.outputs.code == 'true'",
      `${job.id} runs only when the diff touches code`,
    );
  }

  const ci = jobs.get('ci');
  assert.equal(ci?.name, 'CI (all checks)', 'the required check keeps its name');
  assert.ok(ci?.needs.includes('changes'), 'the aggregator waits for the decision');
  assert.equal(ci?.if, 'always()', 'the aggregator reports even when the heavy jobs did not run');
});

test('a pull request touching one code path runs every heavy job', () => {
  assert.equal(
    classify(['backend/src/main/kotlin/app/Main.kt']),
    'code=true',
    'one Kotlin source file is code',
  );
  assert.equal(
    classify([
      'docs/specs/ci-verification.md',
      'backend/src/main/kotlin/app/Main.kt',
      'roadmap/80-docs-only-ci.md',
    ]),
    'code=true',
    'one code path among documentation is enough to run everything',
  );

  // `code=true` is the whole gate: every heavy job's only condition is that output, so none of them
  // can skip a run that touches code.
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const heavy = [...jobs.values()].filter(
    (job) => UNGATED[job.id] !== true && EVENT_GATED[job.id] !== true,
  );
  assert.ok(heavy.length > 0, 'the workflow has heavy jobs to run');
  for (const job of heavy) {
    assert.equal(
      job.if,
      "needs.changes.outputs.code == 'true'",
      `${job.id} runs whenever the diff touches code`,
    );
  }
});

test('changes to .github/, the build files and the lockfiles count as code', () => {
  const code = [
    '.github/workflows/ci.yml',
    '.github/actions/jvm-toolchain/action.yml',
    'devenv.nix',
    'devenv.lock',
    'flake.nix',
    'flake.lock',
    'versions.json',
    'build.gradle.kts',
    'settings.gradle.kts',
    'backend/build.gradle.kts',
    'package.json',
    'frontend/package.json',
    'frontend/pnpm-lock.yaml',
    'desktop/pnpm-lock.yaml',
    'e2e/pnpm-lock.yaml',
  ];
  for (const path of code) {
    assert.equal(classify([path]), 'code=true', `${path} builds or pins something, so it is code`);
  }
});

// Discovered while checking item 3: `docs/api/*.json` are the API contract the backend tests read
// and the `.txt` files are build inputs (`desktop/jlink-modules.txt` for the bundled JRE, the
// whisper fixture for a flake check), so documentation cannot be decided by directory or by `.txt`.
test('a non-Markdown file under docs/ counts as code, and so does every .txt', () => {
  assert.equal(
    classify(['docs/api/languages.json']),
    'code=true',
    'the contract tests read docs/api/*.json',
  );
  assert.equal(classify(['docs/backend-schema.sql']), 'code=true', 'a schema is not prose');
  assert.equal(
    classify(['docs/images/screenshot.png']),
    'code=true',
    'an image is not a Markdown file',
  );

  assert.equal(
    classify(['desktop/jlink-modules.txt']),
    'code=true',
    'the bundled JRE is built from it',
  );
  assert.equal(
    classify(['nix/fixtures/whisper-cpp-sample-transcript.txt']),
    'code=true',
    'a flake check compares whisper output to it',
  );

  assert.equal(
    classify(['docs/specs/ci-verification.md', 'LICENSE']),
    'code=false',
    'Markdown and LICENSE stay documentation',
  );
});

test('a push to main and the nightly schedule run every heavy job', (t) => {
  const repo = tempRepo();
  t.after(() => rmSync(repo.dir, { recursive: true, force: true }));
  const base = repo.commit({ 'README.md': '# base\n' }, 'base');
  const head = repo.commit({ 'docs/specs/ci-verification.md': '# contract\n' }, 'docs only');
  const range = { base, head };

  assert.equal(
    decide(repo.dir, 'pull_request', range),
    'code=false',
    'the range really is documentation only',
  );
  assert.equal(
    decide(repo.dir, 'push', range),
    'code=true',
    'a push runs everything, whatever the range holds',
  );
  assert.equal(
    decide(repo.dir, 'schedule', range),
    'code=true',
    'the nightly schedule runs everything',
  );
  assert.equal(
    decide(repo.dir, 'workflow_dispatch', range),
    'code=true',
    'a dispatch runs everything',
  );
});

test('the ci:full label runs every heavy job on a documentation-only pull request', (t) => {
  const repo = tempRepo();
  t.after(() => rmSync(repo.dir, { recursive: true, force: true }));
  const base = repo.commit({ 'README.md': '# base\n' }, 'base');
  const head = repo.commit({ 'roadmap/80-docs-only-ci.md': '# item\n' }, 'docs only');
  const range = { base, head };

  assert.equal(
    decide(repo.dir, 'pull_request', range),
    'code=false',
    'without the label the docs-only PR skips the heavy jobs',
  );
  assert.equal(
    decide(repo.dir, 'pull_request', range, true),
    'code=true',
    'ci:full forces every heavy job',
  );

  // Adding the label must start a run by itself, and removing it must start the run that goes back
  // to skipping; `types` replaces GitHub's defaults, so they are restated.
  assert.match(
    readFileSync(workflowPath, 'utf8'),
    /^ {2}pull_request:\n {4}types: \[opened, synchronize, reopened, labeled, unlabeled\]$/m,
    'labeling and unlabeling start runs, so ci:full works in both directions without a new commit',
  );

  // The label reaches the script only through the changes job's environment.
  const changes = assertJobOf(parseJobs(readFileSync(workflowPath, 'utf8')), 'changes');
  assert.match(
    changes.raw.join('\n'),
    /FORCE_FULL: \$\{\{ contains\(github\.event\.pull_request\.labels\.\*\.name, 'ci:full'\) \}\}/,
  );
});

test('the required check and the jobs it gates keep their names and conditions', () => {
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const ci = assertJobOf(jobs, 'ci');
  assert.equal(
    ci.name,
    'CI (all checks)',
    'the required check keeps the name branch protection requires',
  );

  // Every heavy job reports through the aggregator, so the gate cannot hide a job from the required
  // check; and a typo in a `needs` entry names nothing and would silently un-gate.
  for (const job of jobs.values()) {
    if (UNGATED[job.id] === true || EVENT_GATED[job.id] === true) continue;
    assert.ok(ci.needs.includes(job.id), `${job.id} is in CI (all checks)`);
  }
  for (const need of ci.needs) {
    assert.ok(jobs.has(need), `ci needs ${need}, which is a job`);
  }
  assert.ok(
    ci.needs.includes('docs-check'),
    'the always-on docs job reports through the aggregator',
  );

  // The manual assets stay dispatch-only and outside the change filter.
  for (const id of [
    'linux-whisper-asset',
    'macos-whisper-asset',
    'macos-ffmpeg-asset',
    'windows-whisper-asset',
    'windows-ffmpeg-asset',
  ]) {
    const job = assertJobOf(jobs, id);
    assert.equal(
      job.if,
      "github.event_name == 'workflow_dispatch' && inputs.assets",
      `${id} keeps its dispatch condition`,
    );
    assert.deepEqual(job.needs, [], `${id} does not wait for the change filter`);
  }

  // Roadmap item 97: the nine per-OS check jobs are one matrix job, so a platform job waits for
  // `check` (all its legs) instead of naming its platform's backend and frontend check ids. The
  // journeys are the one exception (roadmap item 85), pinned in the next test.
  for (const id of ['macos-backend-integration', 'macos-nix', 'macos-dmg', 'windows-desktop-zip']) {
    const job = assertJobOf(jobs, id);
    assert.ok(job.needs.includes('changes'), `${id} carries the change gate`);
    assert.ok(job.needs.includes('check'), `${id} still waits for the platform checks`);
  }

  // The weekly model-host probe stays outside the aggregator on its own cron.
  const modelHosts = assertJobOf(jobs, 'model-hosts');
  assert.equal(
    modelHosts.if,
    "github.event_name == 'schedule' && github.event.schedule == '43 4 * * 1'",
  );
  assert.ok(
    !ci.needs.includes('model-hosts'),
    'a weekly network probe does not gate pull requests',
  );
});

// Roadmap item 85: the journeys are the run's longest jobs, and waiting for their platform's checks
// cost about three minutes of a ~21-minute run (the checks end at +2:50…+3:53). So only the three
// journeys start with the change classification; every other heavy job keeps its platform's checks,
// and the checks stay in the aggregator, so a red check still reddens `CI (all checks)` even when
// the journeys passed.
test('the three journeys start with the change classification, and the checks gate every other job', () => {
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const ci = assertJobOf(jobs, 'ci');

  for (const id of JOURNEYS) {
    assert.deepEqual(
      assertJobOf(jobs, id).needs,
      ['changes'],
      `${id} starts as soon as the change classification has run`,
    );
    assert.ok(ci.needs.includes(id), `${id} still reports through CI (all checks)`);
  }

  // Every other heavy job waits for the one matrix job, which carries all three platforms' checks;
  // the journeys' early start is the exception roadmap item 85 decided, not a rule that spreads.
  for (const id of ci.needs) {
    if (id === 'changes' || id === 'docs-check' || id === 'check' || JOURNEYS.includes(id)) {
      continue;
    }
    assert.ok(
      assertJobOf(jobs, id).needs.includes('check'),
      `${id} still waits for the platform checks`,
    );
  }

  // The check matrix is in the aggregator's needs, not only in the heavy jobs' needs, so a green
  // journey cannot carry a red check leg through.
  assert.ok(ci.needs.includes('check'), 'a failed check leg still fails CI (all checks)');
});

// Roadmap item 97: nine per-OS check jobs become one matrix job. The three platform legs and the
// three checks each stay; only the written-once shape and the display names change, so branch
// protection's required `CI (all checks)` needs `check`, and the docs tests follow the new names.
test('the platform checks are one matrix job on all three platforms', () => {
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const check = assertJobOf(jobs, 'check');
  assert.equal(check.name, 'check (${{ matrix.os }})', 'the matrix leg names its platform');
  assert.deepEqual(check.needs, ['changes'], 'the checks start with the change classification');
  assert.equal(check.if, "needs.changes.outputs.code == 'true'", 'a docs-only PR skips them');
  const raw = check.raw.join('\n');
  assert.match(raw, /^\s*strategy:$/m, 'the job is a matrix');
  assert.match(raw, /os: \[ubuntu-24\.04, macos-15, windows-2025\]/, 'all three platforms run');
  assert.match(raw, /runs-on: \$\{\{ matrix\.os \}\}/, 'each leg picks its runner');

  const commands = stepsOf(check).map(commandsOf).join('\n');
  for (const command of [
    'gradlew.bat checkVersions',
    './gradlew checkVersions',
    '-p backend check',
    'pnpm install --frozen-lockfile',
    'pnpm test',
    'pnpm build',
    'cargo fmt --check',
    'cargo clippy',
    'cargo test',
  ]) {
    assert.ok(commands.includes(command), `the matrix job runs ${command}`);
  }

  // The nine per-OS job ids are gone; the aggregator and every other job name the matrix job.
  for (const old of [
    'backend-check',
    'frontend-check',
    'client-check',
    'macos-backend-check',
    'macos-frontend-check',
    'macos-client-check',
    'windows-backend-check',
    'windows-frontend-check',
    'windows-client-check',
  ]) {
    assert.equal(jobs.has(old), false, `${old} folded into check`);
  }
});

// Roadmap item 84: `Windows (e2e)` left the aggregator while its load flake was open; after five
// consecutive green Windows journeys it gates merges like every other journey — a red Windows
// journey fails `CI (all checks)`.
test('the Windows journey gates merges like every other journey', () => {
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const ci = assertJobOf(jobs, 'ci');
  assert.ok(ci.needs.includes('windows-e2e'), 'Windows (e2e) is in CI (all checks)');
  assert.equal(
    assertJobOf(jobs, 'windows-e2e').name,
    'Windows (e2e)',
    'the gating journey keeps the name branch protection and the aggregator report',
  );
});

// GitHub's `contains(needs.*.result, 'x')` terms of the aggregator's failure step, evaluated against
// a set of job results.
function aggregatorFails(condition: string, results: string[]): boolean {
  const terms = [
    ...condition.matchAll(/contains\(\s*needs\.\*\.result\s*,\s*'([a-z]+)'\s*\)/g),
  ].map((match) => match[1] ?? '');
  assert.ok(
    terms.length > 0,
    `the aggregator's condition is contains(needs.*.result, …) terms: ${condition}`,
  );
  return terms.some((term) => results.includes(term));
}

test('a skipped job does not make CI (all checks) red, and a failing job still does', () => {
  const ci = assertJobOf(parseJobs(readFileSync(workflowPath, 'utf8')), 'ci');
  assert.equal(ci.if, 'always()', 'the aggregator is scheduled even when every heavy job skipped');

  const stepConditions = [...ci.raw.join('\n').matchAll(/^ {8}if: (.+)$/gm)].map((match) =>
    (match[1] ?? '').trim(),
  );
  assert.equal(
    stepConditions.length,
    1,
    'the aggregator has one conditional step: the failure report',
  );
  const [failureCondition = ''] = stepConditions;

  assert.equal(
    aggregatorFails(failureCondition, ['success', 'skipped', 'skipped', 'success']),
    false,
    'skips and successes keep it green',
  );
  assert.equal(
    aggregatorFails(failureCondition, ['success', 'failure', 'skipped']),
    true,
    'one failure reddens it',
  );
  assert.equal(
    aggregatorFails(failureCondition, ['cancelled', 'skipped']),
    true,
    'a cancelled job reddens it',
  );
});

// Roadmap item 85 (discovered): with `cache-read-only: false` on every branch, each pull request
// wrote its own Gradle caches — one PR held ten `gradle-dependencies-v2` entries — and the
// repository's 10 GB cache budget was exceeded, so GitHub evicted entries the whole matrix needs
// (runs #598 and #599: the same bundle derivation hit, then missed). Only `main` writes now; a pull
// request restores `main`'s entries, which GitHub allows for the default branch.
test('only main writes the Gradle cache; pull requests restore it', () => {
  const action = readFileSync(join(repoRoot, '.github/actions/jvm-toolchain/action.yml'), 'utf8');
  assert.match(
    action,
    /^\s*cache-read-only: \$\{\{ github\.ref != 'refs\/heads\/main' \}\}$/m,
    'the JVM toolchain writes the Gradle cache on main only',
  );
});

// Roadmap item 85 (discovered, the root agent's review): the repository stayed 0.31 GB over the free
// plan's 10 GB because a pull request still wrote its own 370 MB `stt-bundle-<derivation>` entry —
// `desktop-bundle-e2e`'s implicit `actions/cache` save. The bundle cache now follows the Gradle
// policy: `desktop-bundle-e2e` restores with the derivation key and only `main` saves, so a pull
// request restores a matching bundle and writes nothing.
test('the bundle cache is saved on main only; pull requests restore it', () => {
  const steps = stepsOf(
    assertJobOf(parseJobs(readFileSync(workflowPath, 'utf8')), 'desktop-bundle-e2e'),
  );
  const cache = (action: string): string | undefined =>
    steps.find(
      (step) =>
        step.includes(action) && step.includes('stt-bundle-${{ steps.bundle-drv.outputs.key }}'),
    );
  assert.ok(
    cache('actions/cache/restore@'),
    'desktop-bundle-e2e restores the bundle cache with actions/cache/restore, keyed by its derivation',
  );
  const save = cache('actions/cache/save@');
  assert.ok(save, 'desktop-bundle-e2e saves the bundle cache on a miss');
  // The condition's wording may change; that it saves on `main` and only on a miss may not.
  assert.match(
    save ?? '',
    /if: .*github\.ref == 'refs\/heads\/main'/,
    'only main saves the bundle cache; a pull request restores it and writes nothing',
  );
});

// Roadmap item 97: the CI spec's sentence is that every platform's check job runs the version-pin
// gate, but only Windows did. The guard reads whatever job carries the backend build, so it holds
// over the per-OS jobs and over the matrix job that replaces them.
test('every job that runs the backend build also runs checkVersions', () => {
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const builds = [...jobs.values()].filter((job) =>
    stepsOf(job).some((step) => /\bgradlew(?:\.bat)?\s+-p backend check\b/.test(commandsOf(step))),
  );
  assert.ok(builds.length > 0, 'a job runs the backend build');
  for (const job of builds) {
    assert.ok(
      stepsOf(job).some((step) => /\bgradlew(?:\.bat)?\s+checkVersions\b/.test(commandsOf(step))),
      `${job.id} runs the backend build, so it runs checkVersions too`,
    );
  }
});

// Roadmap item 97: the cache-key computation was copied into both toolchain actions. One composite
// action and one script now compute it, and a pin change still invalidates the caches it did
// before: the downloads cache on versions.json's pin set, the Nix store cache on the pins plus the
// lock, devenv and nix files.
test('the pins hash is computed by one script, and a pin change changes it', (t) => {
  for (const action of ['jvm-toolchain', 'nix-toolchain']) {
    const text = readFileSync(join(repoRoot, `.github/actions/${action}/action.yml`), 'utf8');
    assert.match(
      text,
      /uses: \.\/\.github\/actions\/pins-hash/,
      `${action} computes its key through the pins-hash action`,
    );
  }

  const dir = mkdtempSync(join(tmpdir(), 'stt-pins-hash-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  for (const path of ['versions.json', 'flake.lock', 'devenv.lock', 'devenv.yaml', 'devenv.nix']) {
    writeFileSync(join(dir, path), readFileSync(join(repoRoot, path)));
  }
  cpSync(join(repoRoot, 'nix'), join(dir, 'nix'), { recursive: true });

  // Run the script the composite action runs, in a tree whose pins can be changed.
  const hash = (...args: string[]): string =>
    execFileSync('bash', [join(repoRoot, '.github/pins-hash.sh'), ...args], {
      cwd: dir,
      encoding: 'utf8',
    }).trim();
  const downloads = hash();
  const nixStore = hash('--nix');
  assert.match(downloads, /^[0-9a-f]{64}$/, 'the downloads key is a sha256');
  assert.match(nixStore, /^[0-9a-f]{64}$/, 'the Nix key is a sha256');
  assert.notEqual(nixStore, downloads, 'the Nix key covers the lock and nix files too');

  const manifest = JSON.parse(readFileSync(join(dir, 'versions.json'), 'utf8')) as {
    downloads: Record<string, Record<string, { sha256: string }>>;
  };
  const ffmpeg = manifest.downloads.ffmpeg?.['x86_64-linux'];
  assert.ok(ffmpeg, 'the manifest pins the Linux ffmpeg');
  ffmpeg.sha256 = '0'.repeat(64);
  writeFileSync(join(dir, 'versions.json'), JSON.stringify(manifest));
  assert.notEqual(hash(), downloads, 'a pin change invalidates the downloads cache');
  assert.notEqual(hash('--nix'), nixStore, 'a pin change invalidates the Nix store cache');
});
