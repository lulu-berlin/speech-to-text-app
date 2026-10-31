// Bounded waits for the packaged-desktop specs' teardown and installer calls (roadmap item 84):
// run #443 sat 53 minutes in the MSI spec with no trace, because nothing it awaited had a ceiling.
// A close that never resolves, or a child process that never exits, is killed at its deadline and
// reported, so the spec fails fast and the evidence steps after it still run.
import { spawn } from 'node:child_process';

export interface CloseOptions {
  /** How long the close may take before [kill] runs. */
  deadlineMs: number;
  /** What is being closed, for the report ("the desktop app"). */
  what: string;
  /** Ends it by force; called once, when the deadline passes. */
  kill: () => void | Promise<void>;
}

/**
 * Runs [close] against a deadline: `closed` when it settles in time, `killed` when the deadline
 * passed first — then [CloseOptions.kill] has run and a warning names what was killed. A close
 * that rejects in time rejects this too.
 */
export async function closeWithin(
  close: () => Promise<unknown>,
  { deadlineMs, what, kill }: CloseOptions,
): Promise<'closed' | 'killed'> {
  const closing = close().then(() => 'closed' as const);
  // A close that rejects after the deadline has nobody left to hear it.
  closing.catch(() => {});
  const deadline = Promise.withResolvers<'killed'>();
  const timer = setTimeout(() => deadline.resolve('killed'), deadlineMs);
  try {
    const outcome = await Promise.race([closing, deadline.promise]);
    if (outcome === 'killed') {
      await kill();
      console.warn(`${what} did not close within ${deadlineMs} ms; killed it`);
    }
    return outcome;
  } finally {
    clearTimeout(timer);
  }
}

export interface Execution {
  code: number | null;
  stdout: string;
  stderr: string;
}

/**
 * Runs [file] with [args] asynchronously and waits at most [ceilingMs] (3 minutes by default) for it
 * to exit; a process still running then is killed, and the call rejects naming it. Any exit code
 * resolves: the caller decides which codes mean success (msiexec's 3010 does).
 */
export async function execFileWithin(
  file: string,
  args: string[],
  { ceilingMs = 180_000 }: { ceilingMs?: number } = {},
): Promise<Execution> {
  const child = spawn(file, args, { windowsHide: true });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', (chunk: string) => {
    stdout += chunk;
  });
  child.stderr.setEncoding('utf8').on('data', (chunk: string) => {
    stderr += chunk;
  });
  const exited = Promise.withResolvers<number | null>();
  child.once('error', exited.reject);
  child.once('close', (code) => exited.resolve(code));
  let code: number | null = null;
  const what = `the ${file} process`;
  const outcome = await closeWithin(
    async () => {
      code = await exited.promise;
    },
    { deadlineMs: ceilingMs, what, kill: () => void child.kill('SIGKILL') },
  );
  if (outcome === 'killed') {
    throw new Error(`${what} (${args.join(' ')}) did not exit within ${ceilingMs} ms; killed it`);
  }
  return { code, stdout, stderr };
}
