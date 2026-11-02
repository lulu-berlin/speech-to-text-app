// The app-mode service the item-66 e2e specs run against (roadmap item 66): the backend as the
// per-user service, on its own data, config and runtime directories and port, plus the `stt-app`
// built from this checkout. The shared dev-mode backend of the journey is untouched.
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { choosePort, startService, sttAppPath, stopBackend } from '../../lib/backend.ts';

export interface AppModeService {
  port: number;
  root: string;
  dataDir: string;
  configDir: string;
  runtimeDir: string;
  sttApp: string;
  env: NodeJS.ProcessEnv;
  stop: () => Promise<void>;
}

/** Starts the service in app mode; always `stop()` it in the spec's `finally`. */
export async function startAppModeService(): Promise<AppModeService> {
  const root = mkdtempSync(join(tmpdir(), 'stt-e2e-service-'));
  const dataDir = join(root, 'data');
  const configDir = join(root, 'config');
  const runtimeDir = join(root, 'runtime');
  for (const dir of [dataDir, configDir, runtimeDir]) mkdirSync(dir);
  const port = await choosePort();
  const { pid } = await startService({ port, dataDir, configDir, runtimeDir });
  const env = { ...process.env, STT_CONFIG_DIR: configDir, STT_RUNTIME_DIR: runtimeDir };
  return {
    port,
    root,
    dataDir,
    configDir,
    runtimeDir,
    sttApp: sttAppPath(),
    env,
    stop: async () => {
      await stopBackend(pid, port, dataDir);
      rmSync(root, { recursive: true, force: true });
    },
  };
}

/** Registers one frontend with the service, as its installer would (`stt-app register`). */
export function registerFrontend(
  service: AppModeService,
  descriptor: { id: string; name: string; kind: string } & Record<string, unknown>,
): void {
  const file = join(service.root, `${descriptor.id}.json`);
  writeFileSync(file, JSON.stringify({ version: 1, ...descriptor }));
  execFileSync(service.sttApp, ['register', file], { env: service.env });
}

/** The one-time launch URL `stt-app open` prints for the browser frontend. */
export function browserLaunchUrl(service: AppModeService): string {
  return execFileSync(service.sttApp, ['open', 'browser', '--print-url'], {
    env: service.env,
    encoding: 'utf8',
  }).trim();
}
