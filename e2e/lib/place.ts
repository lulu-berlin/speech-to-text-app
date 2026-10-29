// Roadmap item 79, test-list item 5: the type-check sandbox links the package into a temporary tree.
// Windows refuses a file symlink without Developer Mode or an elevated shell (EPERM), so a refused
// file link becomes a copy; a directory is linked as a junction there, which needs no privilege.
import { copyFileSync, statSync, symlinkSync } from 'node:fs';

export type Symlink = (source: string, target: string, type?: 'file' | 'dir' | 'junction') => void;

// What a refused symlink looks like across platforms: Windows' EPERM and EACCES without the
// privilege, ENOTSUP where the filesystem has no links at all.
const REFUSED = new Set(['EPERM', 'EACCES', 'ENOTSUP']);

/**
 * Places [target] as a link to [source]. A file link the OS refuses is copied instead, so the
 * sandbox builds without symlink privileges; a directory is linked as a Windows junction where that
 * is the privilege-free form, and a refused directory link is an error (a copy would be huge).
 */
export function place(
  source: string,
  target: string,
  { symlink = symlinkSync as Symlink } = {},
): void {
  // Only Windows needs the link's kind up front; elsewhere the type is ignored and the source is
  // only examined when a link is refused.
  const type =
    process.platform === 'win32'
      ? statSync(source).isDirectory()
        ? 'junction'
        : 'file'
      : undefined;
  try {
    symlink(source, target, type);
    return;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code ?? '';
    if (!REFUSED.has(code) || statSync(source).isDirectory()) throw error;
  }
  copyFileSync(source, target);
}
