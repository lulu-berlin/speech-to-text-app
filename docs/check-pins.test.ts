// The pin-source check (roadmap item 97): a pin declared twice in the audit has one source again.
// The green run reads the tree; the planted run restores the duplicate to a sandbox and expects the
// check to name it. Run with `node --test docs/check-pins.test.ts` from the repository root.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { repoRoot, runCheck } from './run-check.ts';

const CHECK = 'docs/check-pins.ts';
// Every file the check reads, copied into each planted sandbox.
const PIN_FILES = [
  'versions.json',
  'build.gradle.kts',
  'frontend/build.gradle.kts',
  'devenv.nix',
  '.github/workflows/ci.yml',
  'nix/versions.nix',
  'nix/desktop-artifacts.nix',
];

test('the darwin whisper-cli and ffmpeg pins are declared once', (t) => {
  const green = runCheck(CHECK, []);
  assert.equal(green.status, 0, green.stderr + green.stdout);
  assert.match(green.stdout, /^Every joined pin has one source\.\n$/);

  // The planted tree: the manifest and both Nix pin files, with the darwin sha256 written back
  // into nix/desktop-artifacts.nix instead of read from nix/versions.nix.
  const dir = mkdtempSync(join(tmpdir(), 'stt-pins-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  for (const path of PIN_FILES) {
    mkdirSync(dirname(join(dir, path)), { recursive: true });
    writeFileSync(join(dir, path), readFileSync(join(repoRoot, path)));
  }
  const manifest = JSON.parse(readFileSync(join(repoRoot, 'versions.json'), 'utf8')) as {
    downloads: Record<string, Record<string, { sha256: string }>>;
  };
  const sha256 = manifest.downloads['whisper-cli']?.['aarch64-darwin']?.sha256;
  assert.ok(sha256, 'the manifest pins the darwin whisper-cli sha256');
  writeFileSync(
    join(dir, 'nix/desktop-artifacts.nix'),
    `# planted duplicate: ${sha256}\n${readFileSync(join(repoRoot, 'nix/desktop-artifacts.nix'), 'utf8')}`,
  );
  const red = runCheck(CHECK, [dir]);
  assert.equal(red.status, 1, `the check accepted the duplicate:\n${red.stdout}`);
  assert.match(red.stderr, /desktop-artifacts\.nix repeats the whisper-cli aarch64-darwin sha256/);
});

test('the ffmpeg audio-only asset version is declared once', (t) => {
  const green = runCheck(CHECK, []);
  assert.equal(green.status, 0, green.stderr + green.stdout);

  // The planted tree: the Gradle bootstrap restates the version instead of reading versions.json.
  const dir = mkdtempSync(join(tmpdir(), 'stt-pins-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  for (const path of PIN_FILES) {
    mkdirSync(dirname(join(dir, path)), { recursive: true });
    writeFileSync(join(dir, path), readFileSync(join(repoRoot, path)));
  }
  const manifest = JSON.parse(readFileSync(join(repoRoot, 'versions.json'), 'utf8')) as {
    ffmpegAsset?: string;
  };
  assert.ok(manifest.ffmpegAsset, 'the manifest declares the ffmpeg asset version');

  // The version in the `let` but not exported: the flake and desktop-artifacts read the attrset.
  const versionsNix = readFileSync(join(repoRoot, 'nix/versions.nix'), 'utf8');
  writeFileSync(
    join(dir, 'nix/versions.nix'),
    versionsNix.replace(/^\s*inherit ffmpegAsset;\n/m, ''),
  );
  let red = runCheck(CHECK, [dir]);
  assert.equal(red.status, 1, `the check accepted the unexported pin:\n${red.stdout}`);
  assert.match(red.stderr, /must export ffmpegAsset in its returned attribute set/);
  writeFileSync(join(dir, 'nix/versions.nix'), versionsNix);

  writeFileSync(
    join(dir, 'build.gradle.kts'),
    `${readFileSync(join(repoRoot, 'build.gradle.kts'), 'utf8')}\n// planted pin: ${manifest.ffmpegAsset}\n`,
  );
  red = runCheck(CHECK, [dir]);
  assert.equal(red.status, 1, `the check accepted the hardcoded version:\n${red.stdout}`);
  assert.match(red.stderr, /build\.gradle\.kts hardcodes the ffmpeg asset version/);
});

test('the model hashes, node home and Temurin JDK each have one source', (t) => {
  const green = runCheck(CHECK, []);
  assert.equal(green.status, 0, green.stderr + green.stdout);

  // One sandbox, three planted regressions: a model hash restated in devenv.nix, the node path
  // rebuilt in the frontend build, and a Temurin URL on another JDK patch than the manifest's.
  const dir = mkdtempSync(join(tmpdir(), 'stt-pins-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const originals = new Map<string, string>();
  for (const path of PIN_FILES) {
    mkdirSync(dirname(join(dir, path)), { recursive: true });
    const content = readFileSync(join(repoRoot, path), 'utf8');
    originals.set(path, content);
    writeFileSync(join(dir, path), content);
  }
  const manifest = JSON.parse(originals.get('versions.json') ?? '{}') as {
    jdk?: string;
    models?: Record<string, { sha256: string }>;
  };
  const whisperSha = manifest.models?.whisper?.sha256;
  const jdk = manifest.jdk;
  assert.ok(whisperSha, 'the manifest pins the whisper model');
  assert.ok(jdk, 'the manifest pins the JDK');

  const restore = (path: string): void => {
    writeFileSync(join(dir, path), originals.get(path) ?? '');
  };

  writeFileSync(
    join(dir, 'devenv.nix'),
    `${originals.get('devenv.nix')}\n# planted: ${whisperSha}\n`,
  );
  let red = runCheck(CHECK, [dir]);
  assert.equal(red.status, 1, `the check accepted the model hash:\n${red.stdout}`);
  assert.match(red.stderr, /devenv\.nix hardcodes the whisper model sha256/);
  restore('devenv.nix');

  writeFileSync(
    join(dir, 'frontend/build.gradle.kts'),
    (originals.get('frontend/build.gradle.kts') ?? '').replace(
      'rootProject.extra["sttNodeHome"] as File',
      'File(gradle.gradleUserHomeDir, "stt-app/node")',
    ),
  );
  red = runCheck(CHECK, [dir]);
  assert.equal(red.status, 1, `the check accepted the rebuilt node path:\n${red.stdout}`);
  assert.match(red.stderr, /frontend\/build\.gradle\.kts must read the root's sttNodeHome/);
  restore('frontend/build.gradle.kts');

  const tampered = JSON.parse(originals.get('versions.json') ?? '{}') as {
    downloads: Record<string, Record<string, { url: string }>>;
  };
  const temurin = tampered.downloads['temurin-jdk']?.['x86_64-linux'];
  assert.ok(temurin, 'the manifest pins the Linux Temurin JDK');
  temurin.url = temurin.url.replace(encodeURIComponent(jdk), encodeURIComponent('21.0.12.0+1'));
  writeFileSync(join(dir, 'versions.json'), JSON.stringify(tampered));
  red = runCheck(CHECK, [dir]);
  assert.equal(red.status, 1, `the check accepted the Temurin drift:\n${red.stdout}`);
  assert.match(red.stderr, /temurin-jdk\.x86_64-linux url does not name the manifest's jdk/);
});

// Roadmap item 97: the Mesa checks restated nix/artifact-integrity-check.nix's gates. They call the
// module now; only the extra assertion — no registered Mesa's own derivation takes the source Mesa
// as a direct input — stays in flake.nix, and the separate stale-pin check is gone because the
// module carries that gate too. The Nix evaluation itself is the user's.
test('the Mesa integrity checks use the shared artifact module', () => {
  const flake = readFileSync(join(repoRoot, 'flake.nix'), 'utf8');
  const start = flake.indexOf('desktop-mesa-artifact-integrity = let');
  const end = flake.indexOf('desktop-mesa-prebuilt =', start);
  assert.ok(
    start >= 0 && end > start,
    'flake.nix has the Mesa integrity check before the prebuilt check',
  );
  const check = flake.slice(start, end);
  assert.match(
    check,
    /import \.\/nix\/artifact-integrity-check\.nix/,
    'the Mesa check calls the shared module',
  );
  assert.match(check, /attr = "mesa";/, 'the module reads the Mesa result');
  assert.match(check, /artifactsKey = "mesaVulkan";/, 'the module overrides the mesaVulkan table');
  assert.match(
    check,
    /own derivations depend on the source Mesa/,
    'the direct-source-Mesa assertion stays',
  );
  assert.doesNotMatch(
    flake,
    /desktop-mesa-stale-pin/,
    'the separate stale-pin check is gone (the module carries it)',
  );
});
