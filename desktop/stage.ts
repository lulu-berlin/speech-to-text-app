// Roadmap item 50: stages the desktop package's app files into dist/stage, which
// electron-builder then packs into the .deb:
//   dist/stage/jre      jlink runtime image (generic Linux)
//   dist/stage/backend  backend/build/install/stt-backend/lib/ (installDist: every
//                       dependency jar, onnxruntime/sqlite natives included — decided
//                       2026-10-14: no fat-jar plugin)
//   dist/stage/ui       frontend/dist
//   dist/stage/tools    the pinned whisper-cli and ffmpeg (versions.json), flat, with
//                       their .so libraries; backend.ts adds the dir to PATH and
//                       LD_LIBRARY_PATH when it spawns the backend
//
// The tools and the Temurin JDK are fetched once from the versions.json pins into
// dist/downloads and sha256-verified, like ./gradlew prepareRuntime does for the Gradle
// cache. The backend and frontend are NOT built here: the scripts run the repo's builds.
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { downloadVerified, pin } from './verified-download.ts';

const desktopRoot = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(desktopRoot, '..');
const stage = join(desktopRoot, 'dist', 'stage');

const fail = (message: string): never => {
  console.error(`stage: ${message}`);
  process.exit(1);
};

// The pinned artifacts, downloaded once into dist/downloads and sha256-verified against
// versions.json — the same contract as ./gradlew prepareRuntime (see verified-download.ts).
// The cache names are the ones earlier runs already left in dist/downloads; changing them
// would make every run re-download.
const artifact = (key: string): string => {
  const { url, sha256 } = pin(key, 'stage');
  return downloadVerified({
    key,
    label: 'stage',
    cacheName: `${sha256.slice(0, 12)}-${url.slice(url.lastIndexOf('/') + 1)}`,
  });
};

// ---------------------------------------------------------------------------
// Backend JVM, UI and tools
// ---------------------------------------------------------------------------
function stageBackendAndUi(): void {
  const backendInstall = join(repoRoot, 'backend', 'build', 'install', 'stt-backend');
  if (!existsSync(join(backendInstall, 'lib', 'stt-backend-0.1.0-SNAPSHOT.jar'))) {
    fail('backend not built — run: cd backend && gradle installDist');
  }
  const ui = join(repoRoot, 'frontend', 'dist');
  if (!existsSync(join(ui, 'index.html')))
    fail('frontend not built — run: pnpm --dir frontend build');
  rmSync(join(stage, 'backend'), { recursive: true, force: true });
  rmSync(join(stage, 'ui'), { recursive: true, force: true });
  cpSync(join(backendInstall, 'lib'), join(stage, 'backend', 'lib'), { recursive: true });
  cpSync(ui, join(stage, 'ui'), { recursive: true });
}

function stageTools(): void {
  rmSync(join(stage, 'tools'), { recursive: true, force: true });
  const toolsBin = join(stage, 'tools', 'bin');
  mkdirSync(toolsBin, { recursive: true });

  // whisper-cli: the jiang1997 prebuild (glibc >= 2.38, Vulkan), a tar.gz of one directory.
  // Its shared libraries (libwhisper.so.*, libggml*.so) resolve through RUNPATH $ORIGIN, so
  // everything must sit flat in tools/bin.
  const whisper = artifact('whisper-cli');
  spawnSync('tar', ['-xzf', whisper, '-C', toolsBin, '--strip-components=1'], { stdio: 'inherit' });
  if (!existsSync(join(toolsBin, 'whisper-cli'))) fail('whisper-cli not in the extracted tarball');

  // ffmpeg: bytedeco's jar is a zip carrying the executable and its libav*/libva/drm
  // libraries under one directory, whose flat contents belong next to the binary. The
  // libraries carry no RUNPATH, so backend.ts sets LD_LIBRARY_PATH to tools/bin itself.
  const ffmpeg = artifact('ffmpeg');
  const ffmpegTmp = join(toolsBin, 'ffmpeg-tmp');
  mkdirSync(ffmpegTmp, { recursive: true });
  // `jar` (the JDK's) rather than unzip: no unzip on a Nix shell.
  spawnSync('jar', ['xf', ffmpeg], { cwd: ffmpegTmp, stdio: 'inherit' });
  const libs = readdirSync(join(ffmpegTmp, 'org', 'bytedeco', 'ffmpeg', 'linux-x86_64-gpl'), {
    withFileTypes: true,
  });
  for (const entry of libs) {
    copyTo(
      join(ffmpegTmp, 'org', 'bytedeco', 'ffmpeg', 'linux-x86_64-gpl', entry.name),
      join(toolsBin, entry.name),
    );
  }
  rmSync(ffmpegTmp, { recursive: true, force: true });
  if (!existsSync(join(toolsBin, 'ffmpeg'))) fail('ffmpeg not in the extracted jar');
  // A zip stores no file modes, so `jar xf` left the executable non-executable. The backend
  // runs ffmpeg (and ffprobe) by name from PATH (backend.ts prepends tools/bin), so the bit has
  // to be restored here — otherwise the packaged app fails every task with EACCES. The
  // tar-extracted whisper-cli keeps its 0755 from the archive; the jlink JRE keeps its own.
  for (const executable of ['ffmpeg', 'ffprobe']) {
    chmodSync(join(toolsBin, executable), 0o755);
  }
}

function copyTo(from: string, to: string): void {
  if (existsSync(to)) rmSync(to, { recursive: true, force: true });
  cpSync(from, to, { recursive: true });
}

// ---------------------------------------------------------------------------
// The JRE: jlink over the pinned Temurin JDK's jmods. The Nix jlink (devenv JDK) links a
// generic-Linux image from them, so the .deb runs on any glibc distro. The module list is
// jlink-modules.txt, next to this script, because the flake package builds its JRE from the same
// list (nix/desktop.nix) — this list is what derives it.
// ---------------------------------------------------------------------------
const JLINK_MODULES = readFileSync(join(desktopRoot, 'jlink-modules.txt'), 'utf8')
  .split('\n')
  .map((line) => line.trim())
  .filter((line) => line !== '' && !line.startsWith('#'));

function stageJre(): void {
  rmSync(join(stage, 'jre'), { recursive: true, force: true });
  // The pinned Temurin JDK's jmods, fetched and extracted by fetch-jdk.ts; the devenv's
  // jlink (a native binary, e.g. Nix's) links them — jlink's own version need not match
  // theirs, it only reads the jmods.
  const jmods = join(desktopRoot, 'dist', 'jdk', 'jmods');
  if (!existsSync(jmods)) {
    fail('the Temurin JDK is not extracted — run: pnpm --dir desktop fetch:jdk');
  }
  const args = [
    '--module-path',
    jmods,
    '--add-modules',
    JLINK_MODULES.join(','),
    '--output',
    join(stage, 'jre'),
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
  ];
  const result = spawnSync('jlink', args, { stdio: 'inherit', env: process.env });
  if (result.error) fail(`jlink could not be spawned: ${result.error.message}`);
  if (result.status !== 0) fail(`jlink failed (exit ${result.status})`);
  if (!existsSync(join(stage, 'jre', 'bin', 'java'))) fail('jlink produced no bin/java');
  console.log(`stage: jlink image at dist/stage/jre (${JLINK_MODULES.join(', ')})`);
}

// The app's own pins live in versions.json (the `models` section the flake renders from
// nix/models.nix, roadmap item 50 test item 3): the packaged app reads them at run time.
function stageVersions(): void {
  copyTo(join(repoRoot, 'versions.json'), join(stage, 'versions.json'));
}

// ---------------------------------------------------------------------------
rmSync(stage, { recursive: true, force: true });
mkdirSync(stage, { recursive: true });
stageBackendAndUi();
stageTools();
stageJre();
stageVersions();
console.log('stage: ready — run electron-builder to produce the .deb');
