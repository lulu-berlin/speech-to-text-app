// Roadmap item 84: the one way both journey paths build the UI. Gradle's `e2eFrontend` and a
// direct Playwright run both reach it through ensureUiBuilt(), so the build command is written once.
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { shellArgs, shellProgram } from './managed-pnpm.ts';

/**
 * Runs the frontend's own build (`pnpm run build`: `tsc --noEmit && vite build`) from [root].
 * The managed pnpm Gradle hands over wins; a direct run uses the pnpm on PATH.
 */
export async function buildUi({
  root,
  pnpm = process.env.STT_E2E_PNPM ?? 'pnpm',
  platform = process.platform,
}: {
  root: string;
  pnpm?: string;
  platform?: NodeJS.Platform;
}): Promise<void> {
  const args = ['--dir', join(root, 'frontend'), 'run', 'build'];
  const { promise, resolve, reject } = Promise.withResolvers<void>();
  const child = spawn(shellProgram(pnpm, platform), shellArgs(args, platform), {
    cwd: root,
    stdio: 'inherit',
    shell: platform === 'win32',
  });
  child.once('error', (error) => reject(new Error(`could not start ${pnpm}: ${error.message}`)));
  child.once('exit', (code, signal) => {
    if (code === 0) resolve();
    else reject(new Error(`the frontend build failed (${signal ?? `exit ${code}`})`));
  });
  await promise;
}
