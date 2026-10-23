// Roadmap item 50: stages the desktop package's app files into dist/stage, which
// electron-builder then packs into the .deb:
//   dist/stage/jre      jlink runtime image (generic Linux)
//   dist/stage/backend  backend/build/install/stt-backend/lib/ (installDist: every
//                       dependency jar, onnxruntime/sqlite natives included — decided
//                       2026-10-14: no fat-jar plugin)
//   dist/stage/ui       frontend/dist
//   dist/stage/tools    the pinned whisper-cli and ffmpeg (versions.json), flat — on Linux
//                       with their .so libraries, on Apple Silicon as single binaries
//                       (whisper-cli static, ffmpeg linking only system libraries);
//                       backend.ts adds the dir to PATH and LD_LIBRARY_PATH when it spawns
//                       the backend
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
const windows = process.platform === 'win32';

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

function findFile(root: string, name: string): string | undefined {
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      const found = findFile(path, name);
      if (found !== undefined) return found;
    } else if (entry.name === name) {
      return path;
    }
  }
  return undefined;
}

function stageTools(): void {
  rmSync(join(stage, 'tools'), { recursive: true, force: true });
  const toolsBin = join(stage, 'tools', 'bin');
  mkdirSync(toolsBin, { recursive: true });

  // whisper-cli: Linux ships the jiang1997 prebuild (Vulkan), a tar.gz of one directory whose
  // libraries resolve through RUNPATH $ORIGIN and whose relative symlinks (libwhisper.so.1 ->
  // libwhisper.so.1.8.4) must stay relative — tar preserves them; cpSync would make them absolute
  // into a temp dir. macOS ships our static Metal build, a tar.gz with bin/whisper-cli (roadmap
  // item 48), which is extracted and flattened here.
  const whisper = artifact('whisper-cli');
  if (process.platform === 'linux') {
    spawnSync('tar', ['-xzf', whisper, '-C', toolsBin, '--strip-components=1'], {
      stdio: 'inherit',
    });
    if (!existsSync(join(toolsBin, 'whisper-cli')))
      fail('whisper-cli not in the extracted tarball');
  } else {
    const whisperTmp = join(toolsBin, 'whisper-tmp');
    mkdirSync(whisperTmp, { recursive: true });
    spawnSync('tar', ['-xzf', whisper, '-C', whisperTmp], { stdio: 'inherit' });
    const whisperExe = findFile(whisperTmp, windows ? 'whisper-cli.exe' : 'whisper-cli');
    const whisperDir =
      whisperExe === undefined
        ? fail('whisper-cli not in the extracted tarball')
        : dirname(whisperExe);
    for (const entry of readdirSync(whisperDir)) {
      copyTo(join(whisperDir, entry), join(toolsBin, entry));
    }
    rmSync(whisperTmp, { recursive: true, force: true });
  }

  // ffmpeg: on Linux and Windows bytedeco's jar is a zip carrying the executable and its libav*
  // libraries under one directory, whose flat contents belong next to the binary (the libraries
  // carry no RUNPATH; backend.ts sets LD_LIBRARY_PATH on Linux, and the DLLs sit beside the exe on
  // Windows). Apple Silicon ships the pinned audio-only build (roadmap item 69), a .tar.gz with one
  // `bin/ffmpeg`: no libraries and no ffprobe.
  const ffmpeg = artifact('ffmpeg');
  const ffmpegTmp = join(toolsBin, 'ffmpeg-tmp');
  mkdirSync(ffmpegTmp, { recursive: true });
  if (process.platform === 'darwin' && process.arch === 'arm64') {
    spawnSync('tar', ['-xzf', ffmpeg, '-C', ffmpegTmp], { stdio: 'inherit' });
    const exe = findFile(ffmpegTmp, 'ffmpeg') ?? fail('ffmpeg not in the extracted tarball');
    // tar keeps the executable bit; the app runs ffmpeg from PATH (backend.ts prepends
    // tools/bin), so the staged copy must stay executable.
    copyTo(exe, join(toolsBin, 'ffmpeg'));
    chmodSync(join(toolsBin, 'ffmpeg'), 0o755);
    rmSync(ffmpegTmp, { recursive: true, force: true });
  } else {
    const ffmpegClassifier = windows ? 'windows-x86_64-gpl' : 'linux-x86_64-gpl';
    // `jar` (the JDK's) rather than unzip: no unzip on a Nix shell.
    spawnSync('jar', ['xf', ffmpeg], { cwd: ffmpegTmp, stdio: 'inherit' });
    const libs = readdirSync(join(ffmpegTmp, 'org', 'bytedeco', 'ffmpeg', ffmpegClassifier), {
      withFileTypes: true,
    });
    for (const entry of libs) {
      copyTo(
        join(ffmpegTmp, 'org', 'bytedeco', 'ffmpeg', ffmpegClassifier, entry.name),
        join(toolsBin, entry.name),
      );
    }
    rmSync(ffmpegTmp, { recursive: true, force: true });
    if (!existsSync(join(toolsBin, windows ? 'ffmpeg.exe' : 'ffmpeg')))
      fail('ffmpeg not in the extracted jar');
    // A zip stores no file modes, so `jar xf` left the executables non-executable. The backend
    // runs ffmpeg from PATH (backend.ts prepends tools/bin), so the bit has to be restored here.
    if (!windows) {
      for (const executable of ['ffmpeg', 'ffprobe']) {
        if (existsSync(join(toolsBin, executable))) chmodSync(join(toolsBin, executable), 0o755);
      }
    }
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
  if (!existsSync(join(stage, 'jre', 'bin', windows ? 'java.exe' : 'java')))
    fail('jlink produced no bin/java');
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
