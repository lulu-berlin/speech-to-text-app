// Roadmap item 79: the root build's e2e tasks execute pnpm through this launcher (e2e/run.ts).
// Gradle hands it the pinned pnpm path; a gap — no path, or an install that never happened — fails
// with the command that restores it, on every platform, instead of a CreateProcess error or a hang.
import { existsSync } from 'node:fs';

/** The managed pnpm at [pnpm], or an error naming the path and the task that installs it. */
export function requireManagedPnpm(pnpm: string | undefined): string {
  if (pnpm === undefined || pnpm.trim() === '') {
    throw new Error(
      'the managed pnpm path is unset (STT_E2E_PNPM) — run the e2e tasks through Gradle: ./gradlew e2e',
    );
  }
  if (!existsSync(pnpm)) {
    throw new Error(
      `the managed pnpm is missing at ${pnpm} — install it with ./gradlew e2eInstall ` +
        '(Windows: gradlew.bat e2eInstall), then retry',
    );
  }
  return pnpm;
}

/** The part of a spawned pnpm the launcher listens to. */
export interface PnpmProcess {
  once(
    event: 'exit',
    listener: (code: number | null, signal: NodeJS.Signals | null) => void,
  ): unknown;
  once(event: 'error', listener: (error: Error) => void): unknown;
}

export type SpawnPnpm = (pnpm: string, args: string[]) => PnpmProcess;

/**
 * Runs [pnpm] with [args] and ends the launcher the way pnpm ended: a signal is re-raised (so CI
 * sees it), otherwise its exit code, and 1 when it has none. A process that cannot start fails with
 * one line instead of Node's uncaught 'error'.
 */
export function runManagedPnpm(
  pnpm: string,
  args: string[],
  {
    spawn,
    exit = (code: number) => process.exit(code),
    fail = (message: string) => {
      console.error(`e2e: ${message}`);
      process.exit(1);
    },
  }: {
    spawn: SpawnPnpm;
    exit?: (code: number) => void;
    fail?: (message: string) => void;
  },
): void {
  const child = spawn(pnpm, args);
  child.once('error', (error) => fail(`could not start ${pnpm}: ${error.message}`));
  child.once('exit', (code, signal) => {
    if (signal !== null) process.kill(process.pid, signal);
    exit(code ?? 1);
  });
}

/**
 * [value] as cmd.exe must receive it: it splits the command line on spaces and reads `&`, `|`, `^`, `(` and `)` as
 * syntax outside quotes, so a value holding any of those is quoted. Embedded quotes are not re-escaped — a Windows
 * path cannot contain one.
 */
function quoteForCmd(value: string): string {
  return /[\s&|^()]/.test(value) ? `"${value}"` : value;
}

/**
 * [program] as the shell must receive it. Windows' cmd.exe splits the command line it is given on spaces and reads
 * `&`, `|`, `^`, `(` and `)` as syntax, so the program — a path, which never holds a quote — always arrives quoted;
 * without a shell a quote would become part of the file name, so nothing is quoted there.
 */
export function shellProgram(
  program: string,
  platform: NodeJS.Platform = process.platform,
): string {
  return platform === 'win32' ? `"${program}"` : program;
}

/** [args] for the same command line: a value holding whitespace, `&`, `|`, `^`, `(` or `)` is quoted, the rest pass through. */
export function shellArgs(args: string[], platform: NodeJS.Platform = process.platform): string[] {
  return platform === 'win32' ? args.map(quoteForCmd) : args;
}
