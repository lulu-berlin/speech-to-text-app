import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { launchDesktopApp, required } from './helpers/app';

// Roadmap item 74, test-list item "The Nix package and the bundle install the .deb's identity - the
// entry file named `app.stt.desktop.desktop` with `StartupWMClass=app.stt.desktop` - and their
// running window carries that class", plus the .deb half it extends: one identity for every Linux
// package. Desktop environments link a window to its entry by the entry's file name or its
// StartupWMClass, so both must be the app's `desktopName` (package.json), and the window's WM_CLASS
// is that name without the suffix (Electron derives it from the same field). The expected identity
// is the app's as the spec states it, not what the packaging wrote: `app.stt.desktop` is the appId
// (desktop/electron-builder.yml).
const WINDOW_CLASS = 'app.stt.desktop';
const DESKTOP_NAME = `${WINDOW_CLASS}.desktop`;

// Where this package leaves its identity for a desktop to read: the .deb installs it system-wide;
// the Nix package ships it in its store output (`nix profile install` links share/ into the
// profile); the bundle writes it into the user's data directory only when asked.
interface Installed {
  entry: string;
  /** The scalable icon file the Nix package and the bundle install, named by the entry's Icon=. */
  iconFile?: string;
  /** The .deb's hicolor size tree; each size's `<Icon>.png` is checked against its directory. */
  iconSizes?: string;
  /** The entry's Exec value: the launcher this package starts, quoted where the spec requires it. */
  exec: RegExp | string;
}

function installed(): Installed {
  // CI's packaged `.deb` job runs the specs natively (no run-in-docker.ts), so the container's
  // variables are absent there: the helper's defaults are the same ones this spec uses.
  const kind = process.env.STT_DESKTOP_PACKAGE ?? 'deb';
  if (kind === 'deb') {
    return {
      entry: join('/usr/share/applications', DESKTOP_NAME),
      iconSizes: '/usr/share/icons/hicolor',
      exec: /(^|\/)stt-app(\s|$)/,
    };
  }
  const app = required('STT_DESKTOP_APP');
  if (kind === 'nix') {
    const root = dirname(dirname(app));
    return {
      entry: join(root, 'share/applications', DESKTOP_NAME),
      iconFile: join(root, 'share/icons/hicolor/scalable/apps', 'stt-app.svg'),
      exec: join(root, 'bin/stt-app'),
    };
  }
  execFileSync('sh', [app, '--install-desktop-entry'], { stdio: 'inherit' });
  const data = process.env.XDG_DATA_HOME ?? join(required('HOME'), '.local/share');
  return {
    entry: join(data, 'applications', DESKTOP_NAME),
    iconFile: join(data, 'icons/hicolor/scalable/apps', 'stt-app.svg'),
    exec: `"${app}"`,
  };
}

test('the package installs one menu entry with its icon, named by the app identity', () => {
  const { entry: path, iconFile, iconSizes, exec } = installed();

  // One entry for the app, named by its desktopName.
  expect(
    readdirSync(dirname(path)).filter((name) => name.includes('stt') || name.includes('Speech')),
  ).toEqual([DESKTOP_NAME]);
  const entry = desktopEntry(path);
  expect(entry.Name).toBe('Speech-to-Text');
  expect(entry.Type).toBe('Application');
  expect(entry.StartupWMClass).toBe(WINDOW_CLASS);
  expect(entry.Icon).toBe('stt-app');
  expect(entry.Exec).toMatch(exec);

  if (iconSizes !== undefined) {
    // The .deb's Icon= names hicolor PNGs: the 512 size (the app mark the MSI also carries) must
    // be there, and every installed size must be the PNG its directory promises.
    const sizes = readdirSync(iconSizes)
      .filter((name) => /^\d+x\d+$/.test(name))
      .map((size) => ({ size, file: join(iconSizes, size, 'apps', `${entry.Icon}.png`) }))
      .filter(({ file }) => existsSync(file));
    expect(sizes.map(({ size }) => size)).toContain('512x512');
    for (const { size, file } of sizes) {
      const png = readFileSync(file);
      expect(`${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`).toBe(size);
    }
  } else {
    // The Nix package and the bundle install the app's mark as the scalable hicolor icon.
    if (iconFile === undefined) throw new Error('the package reports no scalable icon path');
    expect(readFileSync(iconFile, 'utf8').trimStart().startsWith('<svg')).toBe(true);
  }
});

// The second half: the window a user actually opens must carry the class its entry names, or the
// desktop environment shows two apps (no icon, a second launcher). xprop comes from the devenv
// shell (item 74's tooling), passed in as STT_XPROP; the app runs from its own package.
test('the running window carries the window class its menu entry names', async () => {
  const entry = desktopEntry(installed().entry);

  const app = await launchDesktopApp();
  try {
    const window = await app.firstWindow();
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });

    // Electron's native handle on Linux is the X window id, little-endian in its 4-byte buffer.
    const xid = await app.evaluate(({ BrowserWindow }) => {
      const [first] = BrowserWindow.getAllWindows();
      if (first === undefined) throw new Error('the app has no window');
      return first.getNativeWindowHandle().readUInt32LE(0);
    });
    const xprop = process.env.STT_XPROP ?? 'xprop';
    const classes = execFileSync(xprop, ['-id', String(xid), 'WM_CLASS'], { encoding: 'utf8' });
    // WM_CLASS(STRING) = "<res_name>", "<res_class>": the class is what the entry must match.
    const match = /WM_CLASS\(STRING\) = "[^"]*", "([^"]*)"/.exec(classes);
    expect(match?.[1]).toBe(entry.StartupWMClass);
  } finally {
    await app.close();
  }
});

/** The entry's keys, from its [Desktop Entry] group (the first group of a single-group file). */
function desktopEntry(path: string): Record<string, string> {
  const keys: [string, string][] = readFileSync(path, 'utf8')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.includes('=') && !line.startsWith('#') && !line.startsWith('['))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]);
  return Object.fromEntries(keys);
}
