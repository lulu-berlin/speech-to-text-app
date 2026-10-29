// Roadmap item 79 (discovered): `which` does not exist on Windows and an executable there carries an
// extension, so the harness asks this instead: the PATH entry holding the command, first match wins.
import { existsSync } from 'node:fs';
import { posix, win32 } from 'node:path';

/**
 * Where [command] lives: the PATH entry holding it, or null when nothing on PATH does. The
 * platform decides everything platform-shaped — the PATH separator, the extensions tried (Windows'
 * PATHEXT) and how a directory and a file name join — so a lookup means the same on every host.
 */
export function commandDirectory(
  command: string,
  {
    platform = process.platform,
    pathValue = process.env.PATH ?? '',
    extensions = platform === 'win32' ? (process.env.PATHEXT ?? '.EXE;.CMD;.BAT').split(';') : [''],
    exists = existsSync,
  }: {
    platform?: NodeJS.Platform;
    pathValue?: string;
    extensions?: string[];
    exists?: (file: string) => boolean;
  } = {},
): string | null {
  const path = platform === 'win32' ? win32 : posix;
  for (const directory of pathValue.split(path.delimiter)) {
    if (directory.trim() === '') continue;
    if (extensions.some((extension) => exists(path.join(directory, `${command}${extension}`)))) {
      return directory;
    }
  }
  return null;
}
