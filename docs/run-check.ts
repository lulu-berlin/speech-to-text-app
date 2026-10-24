// One documentation check run by a test (roadmap item 54): its exit status and what it printed.
// The checks read the tree from their working directory, so the caller names it — the repository
// root when the test pins the real tree, a planted sandbox when it pins a failure.
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The repository root: the checks' scripts and the tree they read live here. */
export const repoRoot = fileURLToPath(new URL('..', import.meta.url));

export interface Outcome {
  status: number;
  stdout: string;
  stderr: string;
}

export function runCheck(script: string, args: string[], cwd = repoRoot): Outcome {
  try {
    const stdout = execFileSync(process.execPath, [join(repoRoot, script), ...args], {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { status: 0, stdout, stderr: '' };
  } catch (error) {
    const failure = error as { status?: number; stdout?: string; stderr?: string };
    return {
      status: failure.status ?? -1,
      stdout: failure.stdout ?? '',
      stderr: failure.stderr ?? '',
    };
  }
}
