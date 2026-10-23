// The desktop app's backend lifecycle (roadmap item 50): choose a free loopback port, spawn
// the bundled JRE + installDist lib/ with the app's environment, wait for /api/health, and
// stop cleanly (SIGTERM, then SIGKILL after 10 s). Separated from main.ts so the app wiring
// and the process orchestration stay reviewable on their own.
import {
  type ChildProcess,
  type ChildProcessWithoutNullStreams,
  type StdioOptions,
  spawn,
} from 'node:child_process';
import { existsSync } from 'node:fs';
import { createServer, type AddressInfo } from 'node:net';
import { homedir } from 'node:os';
import { delimiter, join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import type { ModelPaths } from './models.js';

/**
 * The resources layout inside the package (see electron-builder.yml):
 * resourcesDir/{jre,backend/lib,ui,tools/bin}. `resourcesDir` is the app's resource directory
 * — the one app.asar sits in once packed (electron-builder copies extraResources there;
 * verified in dist/linux-unpacked/resources). The caller supplies it (main.ts passes
 * `dirname(app.getAppPath())`), so this module depends on nothing from Electron and a plain
 * Node script can drive the whole lifecycle.
 */
function resources(resourcesDir: string) {
  return {
    jre: join(resourcesDir, 'jre'),
    backendLib: join(resourcesDir, 'backend', 'lib'),
    toolsBin: join(resourcesDir, 'tools', 'bin'),
    staticDir: join(resourcesDir, 'ui'),
  };
}

/**
 * The per-user data directory: on Linux the same ${XDG_DATA_HOME:-~/.local/share}/stt-app the Nix
 * package and ./gradlew use; on macOS ~/Library/Application Support/stt-app (roadmap item 48);
 * on Windows %APPDATA%\stt-app (roadmap item 49). A user-set STT_DATA_DIR wins, like everywhere else.
 */
export function dataDir(): string {
  if (process.env.STT_DATA_DIR) return process.env.STT_DATA_DIR;
  if (process.platform === 'darwin') {
    return join(process.env.HOME ?? homedir(), 'Library', 'Application Support', 'stt-app');
  }
  if (process.platform === 'win32') {
    return join(process.env.APPDATA ?? join(homedir(), 'AppData', 'Roaming'), 'stt-app');
  }
  const base = process.env.XDG_DATA_HOME || join(process.env.HOME ?? homedir(), '.local', 'share');
  return join(base, 'stt-app');
}

// A free loopback port: the app conflicts with nothing that sits on 8080. Between the port
// closing (listener stops) and the backend binding, another process can take it; if the
// backend then fails to start, the caller shows its error and the user retries.
export function freePort(): Promise<number> {
  const { promise, resolve, reject } = Promise.withResolvers<number>();
  const probe = createServer();
  probe.listen(0, '127.0.0.1', () => {
    const { port } = probe.address() as AddressInfo;
    probe.close(() => resolve(port));
  });
  probe.on('error', reject);
  return promise;
}

export async function waitForBackend(
  port: number,
  { timeoutMs = 120_000, intervalMs = 300 } = {},
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/api/health`, {
        signal: AbortSignal.timeout(intervalMs),
      });
      if (response.ok) return;
    } catch {
      // not up yet
    }
    await sleep(intervalMs);
  }
  throw new Error(`the backend did not answer on port ${port} within ${timeoutMs / 1000}s`);
}

export function readyUrl(port: number): string {
  return `http://127.0.0.1:${port}/`;
}

/**
 * Spawns the backend and returns { child, stop }. `stop` asks the backend to shut down
 * (its own lifecycle, item 26, stops whisper and ffmpeg after it) and waits for the JVM to
 * exit, SIGKILL after 10 s — no orphan JVM may survive the app. On Windows it closes the backend's
 * stdin to ask, and ends the whole tree with `taskkill /T /F` only if the JVM outlives the wait,
 * since there are no signals (see [stopBackend]).
 * `resourcesDir` is the packaged resources directory — see `resources()` above.
 * `models` holds the resolved model file paths — a user override, a Nix store file or the
 * verified file in the per-user cache (models.ts); they are the same `STT_*_MODEL` variables
 * the Nix wrapper sets.
 */
export async function startBackend(
  port: number,
  { resourcesDir, models }: { resourcesDir: string; models: ModelPaths },
): Promise<{ child: ChildProcess; stop: () => Promise<void> }> {
  const { jre, backendLib, toolsBin, staticDir } = resources(resourcesDir);
  const java = join(jre, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const backendJar = join(backendLib, 'stt-backend-0.1.0-SNAPSHOT.jar');
  if (!existsSync(java)) throw new Error(`no bundled JRE at ${jre}`);
  if (!existsSync(backendJar)) throw new Error(`no bundled backend at ${backendLib}`);
  if (!existsSync(join(staticDir, 'index.html'))) throw new Error(`no bundled UI at ${staticDir}`);
  const child = spawn(java, ['-cp', join(backendLib, '*'), 'stt.MainKt'], {
    cwd: backendLib,
    env: {
      ...process.env,
      STT_DATA_DIR: dataDir(),
      STT_PORT: String(port),
      // Loopback only (decided 2026-10-14): the API must never be reachable from the
      // network — the port is random per launch; the backend default is 0.0.0.0.
      STT_HOST: '127.0.0.1',
      STT_STATIC_DIR: staticDir,
      STT_WHISPER_MODEL: models.whisper,
      STT_SPEAKER_MODEL: models.speaker,
      // The bundled whisper-cli and ffmpeg come first; a user's PATH still works for the
      // rest. On Linux LD_LIBRARY_PATH makes bytedeco's ffmpeg find its bundled libav*
      // libraries (they carry no RUNPATH); on macOS they resolve through @loader_path and on
      // Windows the DLLs sit beside the exe.
      PATH: `${toolsBin}${process.env.PATH ? `${delimiter}${process.env.PATH}` : ''}`,
      ...(process.platform === 'linux'
        ? {
            LD_LIBRARY_PATH: `${toolsBin}${process.env.LD_LIBRARY_PATH ? `:${process.env.LD_LIBRARY_PATH}` : ''}`,
          }
        : {}),
      // The graceful quit on Windows: [stopBackend] closes this pipe, and the backend stops when it ends.
      ...backendStopEnv(),
    },
    // stdin is the graceful quit channel on Windows (closed by [stopBackend]); elsewhere nothing writes
    // or reads it, so the pipe stays idle. A literal keeps `spawn`'s non-null stdout/stderr typing.
    stdio: backendStdio(),
    // Windows would otherwise flash a console window for the backend.
    windowsHide: true,
  }) as ChildProcessWithoutNullStreams;
  child.stdout.on('data', (chunk: Buffer) => process.stdout.write(`[backend] ${chunk}`));
  child.stderr.on('data', (chunk: Buffer) => process.stderr.write(`[backend] ${chunk}`));
  const stop = (): Promise<void> => stopBackend(child);
  return { child, stop };
}

/** How long a quit waits after asking the backend to stop before it forces it. */
const STOP_TIMEOUT_MS = 10_000;

/** The parts of a spawned backend [stopBackend] needs; a seam so `node --test` can watch a stop without a process. */
export interface StoppableChild {
  readonly pid?: number;
  readonly exitCode: number | null;
  readonly stdin: { end(): void } | null;
  kill(signal?: NodeJS.Signals): boolean;
  once(event: 'exit', listener: () => void): unknown;
}

export interface StopOptions {
  /** Windows asks for the graceful stop by closing stdin; elsewhere SIGTERM is the ask. */
  platform?: NodeJS.Platform;
  /** How long to wait for the exit after the ask before forcing it. */
  timeoutMs?: number;
  /** Windows' force-kill of the tree: true when the backend was ended. A seam for `node --test`. */
  forceKill?: (pid: number) => Promise<boolean>;
  /** Where a failed force-kill is reported; the console by default. */
  log?: (message: string) => void;
}

/**
 * Asks the backend to stop and waits for its exit, so no orphan JVM outlives the app (item 26's
 * lifecycle stops whisper and ffmpeg inside it). On Windows there is no signal to send, so it closes
 * the backend's stdin instead — the backend honours that only under `STT_STOP_ON_STDIN_CLOSE=1`, which
 * [startBackend] sets there — and only force-kills the tree (`taskkill /T /F`) if the JVM outlives the
 * wait. Elsewhere SIGTERM is the ask, SIGKILL the force.
 */
export function stopBackend(
  child: StoppableChild,
  {
    platform = process.platform,
    timeoutMs = STOP_TIMEOUT_MS,
    forceKill = taskkill,
    log = console.warn,
  }: StopOptions = {},
): Promise<void> {
  const { promise, resolve } = Promise.withResolvers<void>();
  if (child.exitCode !== null) {
    resolve();
    return promise;
  }
  child.once('exit', () => resolve());
  if (platform === 'win32') {
    // The backend stops when its stdin closes (STT_STOP_ON_STDIN_CLOSE=1, which startBackend sets there);
    // only a backend that outlives the wait is force-killed with its whole tree.
    child.stdin?.end();
    const forceEnd = async (): Promise<void> => {
      const pid = child.pid ?? 0;
      let ended = false;
      try {
        ended = await forceKill(pid);
      } catch (error) {
        log(`taskkill failed for the backend (pid ${pid}): ${String(error)}`);
      }
      if (ended || child.exitCode !== null) return;
      // taskkill is the only way to reach the tools it spawned; failing that, end the JVM at least.
      log(`taskkill failed for the backend (pid ${pid}); ending the JVM directly`);
      if (!child.kill()) {
        log(`the backend (pid ${pid}) survived taskkill and kill(); not waiting longer`);
        resolve();
      }
    };
    setTimeout(() => {
      if (child.exitCode === null) void forceEnd();
    }, timeoutMs);
    return promise;
  }
  child.kill('SIGTERM');
  setTimeout(() => {
    if (child.exitCode === null) child.kill('SIGKILL');
  }, timeoutMs);
  return promise;
}

/** Windows' force-kill: `taskkill /T /F` ends the backend and every process it spawned; false when it could not. */
async function taskkill(pid: number): Promise<boolean> {
  const { promise, resolve } = Promise.withResolvers<boolean>();
  const killer = spawn('taskkill', ['/pid', String(pid), '/T', '/F'], {
    stdio: 'ignore',
    windowsHide: true,
  });
  killer.once('error', () => resolve(false));
  killer.once('exit', (code) => resolve(code === 0));
  return promise;
}

/**
 * The backend's environment on [platform]: Windows has no signal to stop it with, so the desktop quits it
 * by closing its stdin, which the backend honours only under this switch (roadmap item 49).
 */
export function backendStopEnv(
  platform: NodeJS.Platform = process.platform,
): Record<string, string> {
  return platform === 'win32' ? { STT_STOP_ON_STDIN_CLOSE: '1' } : {};
}

/**
 * The stdio of a spawned backend on [platform]: Windows' stdin is the quit pipe [stopBackend] closes; elsewhere
 * nothing writes or reads it. Both pipe stdout and stderr, so the child's streams are never null.
 */
export function backendStdio(platform: NodeJS.Platform = process.platform): StdioOptions {
  return platform === 'win32' ? ['pipe', 'pipe', 'pipe'] : ['ignore', 'pipe', 'pipe'];
}
