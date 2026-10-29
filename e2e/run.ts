// What the root build's e2e tasks execute (roadmap item 79): Gradle passes the managed pnpm through
// STT_E2E_PNPM, and everything after this script goes to pnpm unchanged. Running it from Node keeps
// Windows' `.cmd` pnpm shim startable and turns a missing install into one clear line.
import { spawn } from 'node:child_process';
import { requireManagedPnpm, runManagedPnpm, shellArgs, shellProgram } from './lib/managed-pnpm.ts';

function managedPnpmOrExit(pnpm: string | undefined): string {
  try {
    return requireManagedPnpm(pnpm);
  } catch (error) {
    console.error(`e2e: ${(error as Error).message}`);
    process.exit(1);
  }
}

// pnpm on Windows is a .cmd shim: only a shell starts it, and its command line is then quoted.
const platform = process.platform;
const useShell = platform === 'win32';

runManagedPnpm(managedPnpmOrExit(process.env.STT_E2E_PNPM), process.argv.slice(2), {
  spawn: (command, args) =>
    spawn(shellProgram(command, platform), shellArgs(args, platform), {
      stdio: 'inherit',
      shell: useShell,
    }),
});
