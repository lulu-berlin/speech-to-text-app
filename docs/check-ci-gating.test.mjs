// The CI gating check (roadmap item 80): a documentation-only pull request must skip every job
// that builds, tests or packages code, while the docs check and the required
// `CI (all checks)` still run. The workflow's own runs are the proof on GitHub; this test pins the
// shape locally and runs the classifier the `changes` job runs on real diffs. Run with
// `node --test docs/check-ci-gating.test.mjs` from the repository root.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const workflowPath = join(repoRoot, '.github/workflows/ci.yml');
const classifierPath = join(repoRoot, '.github/classify-change.sh');
const decidePath = join(repoRoot, '.github/decide-code-change.sh');

// Jobs that must run on every event. `changes` decides, `docs-check` is the cheap always-on
// check, and `ci` is the required aggregator that reports the run's result.
const UNGATED = new Set(['changes', 'docs-check', 'ci']);
// Jobs that are gated by their own event instead: the manual assets (a dispatch with `assets`)
// and the weekly model-host probe (a cron string); none of them is in the aggregator.
const EVENT_GATED = new Set(['macos-whisper-asset', 'macos-ffmpeg-asset', 'windows-whisper-asset', 'model-hosts']);

// The job ids of `.github/workflows/ci.yml`, with the job-level keys this check reads. The file is
// parsed rather than loaded with a YAML library because the repository has none and this check
// must run with only the Node the devenv pins.
function parseJobs(text) {
  const jobs = new Map();
  let inJobs = false;
  let current = null;
  let blockIndent = -1;
  for (const line of text.split('\n')) {
    const indent = line.length - line.trimStart().length;
    if (blockIndent >= 0) {
      if (line.trim() === '' || indent > blockIndent) continue;
      blockIndent = -1;
    }
    if (/^jobs:\s*$/.test(line)) {
      inJobs = true;
      continue;
    }
    if (!inJobs) continue;
    const job = /^ {2}([A-Za-z0-9_-]+):\s*$/.exec(line);
    if (job) {
      current = { id: job[1], name: null, needs: [], if: null, raw: [] };
      jobs.set(current.id, current);
      continue;
    }
    if (current === null) continue;
    current.raw.push(line);
    if (indent !== 4) continue;
    const key = /^ {4}([A-Za-z0-9_-]+):(.*)$/.exec(line);
    if (key === null) continue;
    const [, name, rest] = key;
    const value = rest.trim().replace(/^(['"])(.*)\1$/, '$2');
    if (/^[|>]/.test(value)) {
      blockIndent = 4;
      continue;
    }
    if (name === 'name') current.name = value;
    if (name === 'if') current.if = value;
    if (name === 'needs' && value.startsWith('[')) {
      current.needs = value
        .slice(1, -1)
        .split(',')
        .map((need) => need.trim())
        .filter(Boolean);
    }
  }
  return jobs;
}

// The `code` value the `changes` job reads, from the classifier the job pipes its diff into.
function classify(paths) {
  assert.ok(
    existsSync(classifierPath),
    '.github/classify-change.sh exists: the changes job classifies its diff instead of guessing',
  );
  return execFileSync('bash', [classifierPath], { input: `${paths.join('\n')}\n`, encoding: 'utf8' }).trim();
}

// A throwaway git repository: [commit] writes [files] (relative path -> content) on top of the
// previous commit and returns the new commit id, so a test can hand the decision script a real
// base/head pair.
function tempRepo() {
  const dir = mkdtempSync(join(tmpdir(), 'stt-ci-gating-'));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' }).trim();
  git('init', '-q');
  git('config', 'user.email', 'ci-gating@example.invalid');
  git('config', 'user.name', 'CI gating test');
  return {
    dir,
    commit(files, message) {
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
function decide(dir, event, range = {}, forceFull = false) {
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
    classify(['docs/decisions/0050-pr-merge-on-github.md', 'roadmap/80-docs-only-ci.md', 'README.md', 'LICENSE']),
    'code=false',
    'a diff of Markdown files and LICENSE is documentation only',
  );

  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  assert.ok(jobs.has('changes'), 'the workflow decides code-vs-documentation once, in a changes job');
  const changes = jobs.get('changes');
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
    if (UNGATED.has(job.id) || EVENT_GATED.has(job.id)) continue;
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
  assert.equal(classify(['backend/src/main/kotlin/app/Main.kt']), 'code=true', 'one Kotlin source file is code');
  assert.equal(
    classify(['docs/specs/ci-verification.md', 'backend/src/main/kotlin/app/Main.kt', 'roadmap/80-docs-only-ci.md']),
    'code=true',
    'one code path among documentation is enough to run everything',
  );

  // `code=true` is the whole gate: every heavy job's only condition is that output, so none of them
  // can skip a run that touches code.
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const heavy = [...jobs.values()].filter((job) => !UNGATED.has(job.id) && !EVENT_GATED.has(job.id));
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
  assert.equal(classify(['docs/api/languages.json']), 'code=true', 'the contract tests read docs/api/*.json');
  assert.equal(classify(['docs/backend-schema.sql']), 'code=true', 'a schema is not prose');
  assert.equal(classify(['docs/images/screenshot.png']), 'code=true', 'an image is not a Markdown file');

  assert.equal(classify(['desktop/jlink-modules.txt']), 'code=true', 'the bundled JRE is built from it');
  assert.equal(
    classify(['nix/fixtures/whisper-cpp-sample-transcript.txt']),
    'code=true',
    'a flake check compares whisper output to it',
  );

  assert.equal(classify(['docs/specs/ci-verification.md', 'LICENSE']), 'code=false', 'Markdown and LICENSE stay documentation');
});

test('a push to main and the nightly schedule run every heavy job', (t) => {
  const repo = tempRepo();
  t.after(() => rmSync(repo.dir, { recursive: true, force: true }));
  const base = repo.commit({ 'README.md': '# base\n' }, 'base');
  const head = repo.commit({ 'docs/specs/ci-verification.md': '# contract\n' }, 'docs only');
  const range = { base, head };

  assert.equal(decide(repo.dir, 'pull_request', range), 'code=false', 'the range really is documentation only');
  assert.equal(decide(repo.dir, 'push', range), 'code=true', 'a push runs everything, whatever the range holds');
  assert.equal(decide(repo.dir, 'schedule', range), 'code=true', 'the nightly schedule runs everything');
  assert.equal(decide(repo.dir, 'workflow_dispatch', range), 'code=true', 'a dispatch runs everything');
});

test('the ci:full label runs every heavy job on a documentation-only pull request', (t) => {
  const repo = tempRepo();
  t.after(() => rmSync(repo.dir, { recursive: true, force: true }));
  const base = repo.commit({ 'README.md': '# base\n' }, 'base');
  const head = repo.commit({ 'roadmap/80-docs-only-ci.md': '# item\n' }, 'docs only');
  const range = { base, head };

  assert.equal(decide(repo.dir, 'pull_request', range), 'code=false', 'without the label the docs-only PR skips the heavy jobs');
  assert.equal(decide(repo.dir, 'pull_request', range, true), 'code=true', 'ci:full forces every heavy job');

  // The label reaches the script only through the changes job's environment.
  const changes = parseJobs(readFileSync(workflowPath, 'utf8')).get('changes');
  assert.match(changes.raw.join('\n'), /FORCE_FULL: \$\{\{ contains\(github\.event\.pull_request\.labels\.\*\.name, 'ci:full'\) \}\}/);
});

test('the required check and the jobs it gates keep their names and conditions', () => {
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  const ci = jobs.get('ci');
  assert.equal(ci.name, 'CI (all checks)', 'the required check keeps the name branch protection requires');

  // Every heavy job reports through the aggregator, so the gate cannot hide a job from the required
  // check; and a typo in a `needs` entry names nothing and would silently un-gate.
  for (const job of jobs.values()) {
    if (UNGATED.has(job.id) || EVENT_GATED.has(job.id)) continue;
    assert.ok(ci.needs.includes(job.id), `${job.id} is in CI (all checks)`);
  }
  for (const need of ci.needs) {
    assert.ok(jobs.has(need), `ci needs ${need}, which is a job`);
  }
  assert.ok(ci.needs.includes('docs-check'), 'the always-on docs job reports through the aggregator');

  // The manual assets stay dispatch-only and outside the change filter.
  for (const id of ['macos-whisper-asset', 'macos-ffmpeg-asset']) {
    const job = jobs.get(id);
    assert.equal(job.if, "github.event_name == 'workflow_dispatch' && inputs.assets", `${id} keeps its dispatch condition`);
    assert.deepEqual(job.needs, [], `${id} does not wait for the change filter`);
  }

  // The macOS jobs keep their own dependencies: the change gate is added, not swapped in.
  for (const id of ['macos-backend-integration', 'macos-e2e', 'macos-nix', 'macos-dmg']) {
    const job = jobs.get(id);
    assert.ok(job.needs.includes('changes'), `${id} carries the change gate`);
    assert.ok(job.needs.includes('macos-backend-check'), `${id} still waits for macOS (gradle check)`);
    assert.ok(job.needs.includes('macos-frontend-check'), `${id} still waits for macOS (pnpm test, pnpm build)`);
  }

  // The weekly model-host probe stays outside the aggregator on its own cron.
  const modelHosts = jobs.get('model-hosts');
  assert.equal(modelHosts.if, "github.event_name == 'schedule' && github.event.schedule == '0 4 * * 1'");
  assert.ok(!ci.needs.includes('model-hosts'), 'a weekly network probe does not gate pull requests');
});

// GitHub's `contains(needs.*.result, 'x')` terms of the aggregator's failure step, evaluated against
// a set of job results.
function aggregatorFails(condition, results) {
  const terms = [...condition.matchAll(/contains\(\s*needs\.\*\.result\s*,\s*'([a-z]+)'\s*\)/g)].map((match) => match[1]);
  assert.ok(terms.length > 0, `the aggregator's condition is contains(needs.*.result, …) terms: ${condition}`);
  return terms.some((term) => results.includes(term));
}

test('a skipped job does not make CI (all checks) red, and a failing job still does', () => {
  const ci = parseJobs(readFileSync(workflowPath, 'utf8')).get('ci');
  assert.equal(ci.if, 'always()', 'the aggregator is scheduled even when every heavy job skipped');

  const stepConditions = [...ci.raw.join('\n').matchAll(/^ {8}if: (.+)$/gm)].map((match) => match[1].trim());
  assert.equal(stepConditions.length, 1, 'the aggregator has one conditional step: the failure report');
  const [failureCondition] = stepConditions;

  assert.equal(aggregatorFails(failureCondition, ['success', 'skipped', 'skipped', 'success']), false, 'skips and successes keep it green');
  assert.equal(aggregatorFails(failureCondition, ['success', 'failure', 'skipped']), true, 'one failure reddens it');
  assert.equal(aggregatorFails(failureCondition, ['cancelled', 'skipped']), true, 'a cancelled job reddens it');
});
