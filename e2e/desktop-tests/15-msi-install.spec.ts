import { expect, test } from '@playwright/test';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import {
  delay,
  jfkClip,
  launchDesktopApp,
  required,
  transcribeAndOpenTranscript,
  userPaths,
} from './helpers/app';
import { startModelServer, writeFixturePins } from './helpers/model-server';

// Roadmap item 49 (ADR 0046), test item 12: the unsigned per-user MSI is the app's installer.
// A silent `msiexec /i … /quiet` puts it under the per-user programs directory (not Program
// Files), the installed app runs the same journey with its per-user data and model cache, and
// `msiexec /x {ProductCode} /quiet` takes the program files away while the user's data and the
// downloaded models stay: an uninstall must not cost the user their transcriptions.
//
// The MSI comes from the Windows job (`STT_DESKTOP_MSI`), which builds it from the same staged
// tree as the portable zip. Windows-only: the msi target only builds and runs there.
test('the per-user MSI installs the app, transcribes, and the uninstall keeps the data and models', async () => {
  test.skip(
    process.platform !== 'win32',
    'the MSI target is Windows-only: the Windows packaged-zip job runs this spec',
  );
  const msi = required('STT_DESKTOP_MSI');
  const whisperFile = basename(required('TEST_WHISPER_TINY_EN_MODEL'));
  const speakerFile = basename(required('STT_SPEAKER_MODEL'));
  const programs = join(process.env.LOCALAPPDATA ?? '', 'Programs');

  // A rerun after a failed attempt starts from an uninstalled machine, not from a half state.
  const previous = await productCode();
  if (previous) {
    uninstall(previous);
    await waitFor(() => installedExe() === null, 120_000, 'the previous installation to go away');
  }

  install(msi);
  const exe = await waitFor(installedExe, 180_000, 'the MSI to install Speech-to-Text.exe');
  // Per-user: under %LOCALAPPDATA%\Programs, never under Program Files (the user is not asked
  // for an elevated install).
  expect(exe.startsWith(programs + '\\')).toBe(true);
  // A per-machine install would put the exe under Program Files, not merely outside the per-user
  // directory; both roots must stay untouched by a per-user install.
  expect(installedUnderProgramFiles()).toEqual([]);

  // The installed app is the packaged app: the first run provisions the per-user data directory
  // and downloads the models into the cache (the same loopback server item 50's first-run spec
  // uses), then the shared upload → transcript journey runs inside its window.
  const home = mkdtempSync(join(tmpdir(), 'stt-desktop-msi-'));
  const server = await startModelServer([
    { name: whisperFile, path: required('TEST_WHISPER_TINY_EN_MODEL') },
    { name: speakerFile, path: required('STT_SPEAKER_MODEL') },
  ]);
  const pins = writeFixturePins(server.baseUrl);
  const paths = userPaths(home);
  const app = await launchDesktopApp({
    executable: exe,
    home,
    models: 'download',
    env: { STT_MODELS_FILE: pins },
  });
  try {
    const window = await app.firstWindow();
    await expect(window).toHaveURL(/^http:\/\/127\.0\.0\.1:\d+\/queue$/, { timeout: 300_000 });
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });
    await transcribeAndOpenTranscript(window, jfkClip());
  } finally {
    await app.close();
    await server.close();
  }
  const models = [whisperFile, speakerFile];
  expectUserDataKept(paths, models);

  // The uninstall by the ProductCode the installer registered: the program files go.
  const code = await productCode();
  if (!code) throw new Error('the MSI registered no uninstall entry named Speech-to-Text');
  uninstall(code);
  await waitFor(() => installedExe() === null, 120_000, 'the uninstalled program files to go');

  // What the user keeps: the database with the transcript and the verified model cache.
  expectUserDataKept(paths, models);
});

/** The per-user database and every named model in the cache are on disk. */
function expectUserDataKept(paths: { data: string; models: string }, models: string[]): void {
  expect(existsSync(join(paths.data, 'stt.db'))).toBe(true);
  for (const model of models) expect(existsSync(join(paths.models, model))).toBe(true);
}

/** Every `Speech-to-Text.exe` under the machine-wide Program Files directories, which a per-user install must not touch. */
function installedUnderProgramFiles(): string[] {
  const roots = [process.env.ProgramFiles, process.env['ProgramFiles(x86)']].filter(
    (root): root is string => typeof root === 'string' && root !== '' && existsSync(root),
  );
  return roots.flatMap((root) => {
    const output = execFileSync(
      'powershell.exe',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        `Get-ChildItem -LiteralPath '${root}' -Recurse -Filter 'Speech-to-Text.exe' -File ` +
          '-ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName',
      ],
      { encoding: 'utf8', timeout: 120_000 },
    );
    return output
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line !== '');
  });
}

/** Runs `msiexec` and fails on anything but success (3010 is "success, reboot required"). */
function msiexec(args: string[]): void {
  const result = spawnSync('msiexec.exe', args, { stdio: 'inherit', timeout: 600_000 });
  if (result.error) throw result.error;
  if (result.status !== 0 && result.status !== 3010) {
    throw new Error(`msiexec ${args[0]} failed with exit code ${result.status}`);
  }
}

function install(msi: string): void {
  msiexec(['/i', msi, '/quiet', '/norestart']);
}

function uninstall(productCode: string): void {
  msiexec(['/x', productCode, '/quiet', '/norestart']);
}

/** The installed executable under the per-user programs directory, or null while none is there. */
function installedExe(): string | null {
  const programs = join(process.env.LOCALAPPDATA ?? '', 'Programs');
  if (!existsSync(programs)) return null;
  const found = execFileSync(
    'powershell.exe',
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      `Get-ChildItem -LiteralPath '${programs}' -Recurse -Filter 'Speech-to-Text.exe' -File ` +
        '-ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName',
    ],
    { encoding: 'utf8', timeout: 120_000 },
  ).trim();
  return found === '' ? null : found;
}

/** The ProductCode the installer registered: its uninstall entry's key, per-user first. */
async function productCode(): Promise<string | null> {
  const script = [
    "$paths = @('HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall',",
    "  'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall')",
    'foreach ($path in $paths) {',
    '  if (-not (Test-Path $path)) { continue }',
    '  Get-ChildItem $path | ForEach-Object {',
    '    $entry = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue',
    "    if ($entry.DisplayName -eq 'Speech-to-Text') { $_.PSChildName }",
    '  }',
    '}',
  ].join('\n');
  const output = execFileSync(
    'powershell.exe',
    ['-NoProfile', '-NonInteractive', '-Command', script],
    { encoding: 'utf8', timeout: 120_000 },
  );
  const first = output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line !== '');
  return first ?? null;
}

/** Polls `probe` until it returns non-null (or true), or fails the test with `what` at `timeoutMs`. */
async function waitFor<T>(
  probe: () => T | null | false,
  timeoutMs: number,
  what: string,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = probe();
    if (value) return value;
    if (Date.now() >= deadline)
      throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}`);
    await delay(500);
  }
}
