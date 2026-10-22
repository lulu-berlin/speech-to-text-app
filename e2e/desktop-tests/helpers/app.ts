import {
  _electron as electron,
  expect,
  type ElectronApplication,
  type Page,
} from '@playwright/test';
import { copyFileSync, existsSync, mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { JFK_QUOTE, turnTextOf } from '../../tests/helpers/ui';

// Shared setup for the desktop tests (roadmap item 50): desktop/run-in-docker.ts runs these specs
// in a stock container against one packaged app — the installed .deb's `stt-app` command, the
// flake package's `bin/stt-app` from the read-only /nix/store mount, or the single-file bundle on
// Alpine (ADR 0053). Either way the window, the backend, the UI it serves, the JRE and the tools
// must all come out of the package.
const DESKTOP_APP = process.env.STT_DESKTOP_APP || '/usr/bin/stt-app';

export interface LaunchOptions {
  /** Reuse a HOME across launches (item 3's restart); a fresh one by default. */
  home?: string;
  /**
   * `override` (the default) passes the two models the harness mounts, so the app skips the
   * download; `download` leaves STT_WHISPER_MODEL/STT_SPEAKER_MODEL unset, so the app provisions
   * the models itself (item 3).
   */
  models?: 'override' | 'download';
  /** Extra environment for the launched app (for example STT_MODELS_FILE). */
  env?: Record<string, string>;
}

/**
 * Launches the packaged app the way a user starts it: a fresh HOME (the per-user data directory
 * starts empty) and Chromium's sandbox on.
 */
export function launchDesktopApp(options: LaunchOptions = {}): Promise<ElectronApplication> {
  const { home = temporaryDir('home'), models = 'override', env = {} } = options;
  // A fresh per-user area per launch. Windows apps read %APPDATA%/%LOCALAPPDATA%, not HOME.
  const homeEnv =
    process.platform === 'win32'
      ? {
          USERPROFILE: home,
          APPDATA: join(home, 'AppData', 'Roaming'),
          LOCALAPPDATA: join(home, 'AppData', 'Local'),
        }
      : { HOME: home };
  // Only variables that are set: an unset one has no value to pass to the app.
  const appEnv: Record<string, string> = Object.fromEntries(
    Object.entries({ ...process.env, ...env, ...homeEnv }).filter(
      (entry): entry is [string, string] => entry[1] !== undefined,
    ),
  );
  if (models === 'override') {
    appEnv.STT_WHISPER_MODEL = required('TEST_WHISPER_TINY_EN_MODEL');
    appEnv.STT_SPEAKER_MODEL = required('STT_SPEAKER_MODEL');
  } else {
    // The container harness exports STT_SPEAKER_MODEL for the other specs; item 3 needs both
    // variables absent so the app downloads instead of taking the override.
    delete appEnv.STT_WHISPER_MODEL;
    delete appEnv.STT_SPEAKER_MODEL;
  }
  return electron.launch({
    executablePath: DESKTOP_APP,
    // Playwright adds --no-sandbox on Linux by default; the app runs as shipped, sandboxed.
    chromiumSandbox: true,
    env: appEnv,
  });
}

/**
 * The tail of the journey both the packaged-journey and the first-run spec end on: upload the clip,
 * wait for the app's own finish notice, open the transcript from History and assert that its first
 * turn carries the JFK quote's phrases.
 */
export async function transcribeAndOpenTranscript(window: Page, clip: string): Promise<void> {
  await window.setInputFiles('input[type="file"]', clip);
  await expect(window.getByText('jfk.wav transcribed')).toBeVisible({ timeout: 300_000 });
  await window.getByRole('navigation').getByRole('link', { name: 'History' }).click();
  await window.getByRole('link', { name: 'jfk.wav' }).click();
  await expect(window.getByRole('heading', { name: 'jfk.wav' })).toBeVisible();
  const turn = turnTextOf(window, 'Speaker 1');
  for (const phrase of JFK_QUOTE) {
    await expect(turn).toContainText(phrase);
  }
}

/**
 * The GPU journey of the bundle's GPU specs: the startup probe has to report a GPU whose name
 * matches `name`, the Settings page chooses it, and the JFK transcription then has to finish with
 * that GPU still chosen and no CPU fallback — when whisper-cli cannot use the chosen GPU, the app
 * switches the device to `cpu` and names the GPU in `deviceFallback` (items 32 and 45), so a
 * finished task with the GPU still chosen ran on it.
 */
export async function transcribeOnGpu(window: Page, name: RegExp): Promise<void> {
  await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });
  const settings = (): Promise<{
    device: string;
    deviceFallback: string | null;
    gpuNames: Record<string, string>;
  }> => window.evaluate(() => fetch('/api/settings').then((response) => response.json()));

  const { gpuNames } = await settings();
  const gpu = Object.entries(gpuNames).find(([, gpuName]) => name.test(gpuName));
  if (!gpu)
    throw new Error(`no ${name} device among the probe's GPUs: ${JSON.stringify(gpuNames)}`);
  const [device] = gpu;

  const navigation = window.getByRole('navigation');
  await navigation.getByRole('link', { name: 'Settings' }).click();
  await window.locator('#device').selectOption(device);
  await window.getByRole('button', { name: 'Save' }).click();
  await expect.poll(async () => (await settings()).device).toBe(device);

  await navigation.getByRole('link', { name: 'Queue' }).click();
  await transcribeAndOpenTranscript(window, jfkClip());
  expect(await settings()).toMatchObject({ device, deviceFallback: null });
}

/** The JFK fixture in its own temporary directory: an upload the launched app can read. */
export function jfkClip(): string {
  const clip = join(temporaryDir('clip'), 'jfk.wav');
  copyFileSync(required('TEST_JFK_WAV'), clip);
  return clip;
}

/** The per-user directories a fresh HOME gets (the paths the app decided on, 2026-10-14/22). */
export function userPaths(home: string) {
  if (process.platform === 'darwin') {
    return {
      data: join(home, 'Library', 'Application Support', 'stt-app'),
      models: join(home, 'Library', 'Caches', 'stt-app', 'models'),
    };
  }
  if (process.platform === 'win32') {
    return {
      data: join(home, 'AppData', 'Roaming', 'stt-app'),
      models: join(home, 'AppData', 'Local', 'stt-app', 'models'),
    };
  }
  return {
    data: join(home, '.local', 'share', 'stt-app'),
    models: join(home, '.cache', 'stt-app', 'models'),
  };
}

/** An environment variable the container harness passes; without it the test cannot run. */
export function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is unset — run through desktop/run-in-docker.ts from the devenv shell`,
    );
  }
  return value;
}

/** The backend JVM the app spawns, by its main class. */
export const BACKEND = /stt\.MainKt/;

/** The processes the app owns: the backend JVM, whisper-cli and ffmpeg (Windows paths use `\`). */
export const APP_PROCESSES = /stt\.MainKt|whisper-cli(\.exe)?\b|[\\/]ffmpeg(gui)?(\.exe)?\b/;

/** A running process: its pid and its command line, arguments joined by spaces. */
export interface RunningProcess {
  pid: string;
  command: string;
}

/**
 * The running processes whose command line matches `pattern`. Read from /proc on Linux (a stock
 * ubuntu container has no procps); on macOS, which has no /proc, from `ps`; on Windows from
 * CIM's `Win32_Process`.
 */
export function processes(pattern: RegExp): RunningProcess[] {
  const found: RunningProcess[] = [];
  if (process.platform === 'win32') {
    const output = execFileSync(
      'powershell.exe',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        'Get-CimInstance Win32_Process | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress',
      ],
      { encoding: 'utf8' },
    );
    const parsed = JSON.parse(output) as
      | Array<{ ProcessId: number; CommandLine: string | null }>
      | { ProcessId: number; CommandLine: string | null }
      | null;
    if (parsed === null) return found;
    for (const row of Array.isArray(parsed) ? parsed : [parsed]) {
      const command = row.CommandLine ?? '';
      if (pattern.test(command)) found.push({ pid: String(row.ProcessId), command });
    }
    return found;
  }
  if (existsSync('/proc')) {
    for (const pid of readdirSync('/proc')) {
      if (!/^\d+$/.test(pid)) continue;
      let cmdline: string;
      try {
        cmdline = readFileSync(`/proc/${pid}/cmdline`, 'utf8');
      } catch {
        continue; // the process ended between the readdir and the read
      }
      const command = cmdline.replaceAll('\0', ' ').trim();
      if (pattern.test(command)) found.push({ pid, command });
    }
    return found;
  }
  const rows = execFileSync('ps', ['-axo', 'pid=,command='], { encoding: 'utf8' });
  for (const line of rows.split('\n')) {
    const match = /^\s*(\d+)\s+(.*)$/.exec(line);
    if (match === null) continue;
    const pid = match[1];
    const command = match[2];
    if (pid === undefined || command === undefined) continue;
    if (pattern.test(command)) found.push({ pid, command });
  }
  return found;
}

/**
 * Polls until no process matches `pattern`, or `timeoutMs` is up, and returns the ones still
 * running as `pid: command line` lines: a survivor is what a quit assertion reports.
 */
export async function survivors(pattern: RegExp, timeoutMs: number): Promise<string[]> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const running = processes(pattern).map(({ pid, command }) => `${pid}: ${command}`);
    if (running.length === 0 || Date.now() >= deadline) return running;
    await delay(250);
  }
}

export function delay(ms: number): Promise<void> {
  const { promise, resolve } = Promise.withResolvers<void>();
  setTimeout(resolve, ms);
  return promise;
}

function temporaryDir(kind: string): string {
  return mkdtempSync(join(tmpdir(), `stt-desktop-${kind}-`));
}
