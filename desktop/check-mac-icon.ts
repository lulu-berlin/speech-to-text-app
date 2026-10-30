// Checks the macOS DMG's app icon (roadmap item 74, test-list item "The macOS DMG's app carries the
// app's mark as its icon"): the mounted app's Info.plist names an .icns, that file is a real ICNS
// with a 512 px image, and it is not Electron's default icon - which is what ships when the `mac:`
// section names no icon. Runs where hdiutil and plutil exist: on the macOS desktop leg
// (`node desktop/check-mac-icon.ts`), or by hand with `pnpm --dir desktop check:mac-icon` after
// `pnpm --dir desktop dist:mac`.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const desktopDir = dirname(fileURLToPath(import.meta.url));

function run(tool: string, args: string[]): string {
  try {
    return execFileSync(tool, args, { encoding: 'utf8' });
  } catch (error) {
    throw new Error(
      `${tool} ${args.join(' ')} failed: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}

/** The check proper; any failure is an Error whose message says what is wrong. */
function check(): string {
  if (process.platform !== 'darwin')
    throw new Error('this check reads a macOS app bundle; run it on macOS');

  const dist = join(desktopDir, 'dist');
  const dmgs = existsSync(dist) ? readdirSync(dist).filter((name) => name.endsWith('.dmg')) : [];
  const [dmg] = dmgs;
  if (dmgs.length !== 1 || dmg === undefined) {
    throw new Error(
      `expected exactly one .dmg in ${dist}, found ${dmgs.length} — build it with: pnpm --dir desktop dist:mac`,
    );
  }

  const mount = mkdtempSync(join(tmpdir(), 'stt-dmg-'));
  let attached = false;
  try {
    run('hdiutil', ['attach', '-nobrowse', '-readonly', '-mountpoint', mount, join(dist, dmg)]);
    attached = true;

    const apps = readdirSync(mount).filter((name) => name.endsWith('.app'));
    const [appName] = apps;
    if (apps.length !== 1 || appName === undefined)
      throw new Error(`the DMG mounts ${apps.length} .app bundles, not one`);
    const app = join(mount, appName);

    const plist = JSON.parse(
      run('plutil', ['-convert', 'json', '-o', '-', join(app, 'Contents/Info.plist')]),
    ) as {
      CFBundleIconFile?: unknown;
    };
    const iconName = plist.CFBundleIconFile;
    if (typeof iconName !== 'string' || iconName === '')
      throw new Error('the app bundle has no CFBundleIconFile');
    // electron-builder writes the configured icon as Resources/icon.icns and points the plist at
    // it; with none it leaves the framework's own `electron.icns`, the default this item replaces.
    if (iconName === 'electron.icns')
      throw new Error(
        "the app carries Electron's default icon (CFBundleIconFile=electron.icns), not the app's mark (desktop/icon.png)",
      );
    const icon = join(
      app,
      'Contents/Resources',
      extname(iconName) === '' ? `${iconName}.icns` : iconName,
    );
    if (!existsSync(icon))
      throw new Error(`CFBundleIconFile names ${iconName}, but ${icon} does not exist`);

    const icns = readFileSync(icon);
    if (icns.subarray(0, 4).toString('ascii') !== 'icns')
      throw new Error(`${iconName} is not an ICNS file`);

    // The icon table: 'icns', a big-endian total length, then chunks of a 4-byte type and a 4-byte
    // big-endian length (including those 8 bytes) plus payload. ic09 is 512 px, ic10 1024 px.
    const types: string[] = [];
    for (let offset = 8; offset + 8 <= icns.length;) {
      const type = icns.subarray(offset, offset + 4).toString('ascii');
      const length = icns.readUInt32BE(offset + 4);
      if (length < 8) throw new Error(`${iconName} has a malformed ${type} chunk`);
      types.push(type);
      offset += length;
    }
    if (!types.includes('ic09') && !types.includes('ic10')) {
      throw new Error(`${iconName} has no 512 px image (its images: ${types.join(' ')})`);
    }

    return `${appName}'s ${iconName}: ${types.length} images`;
  } finally {
    // A failed unmount must not replace the error that explains the check's failure.
    if (attached) {
      try {
        run('hdiutil', ['detach', mount, '-quiet']);
      } catch (error) {
        console.error(
          `check-mac-icon: could not unmount ${mount}: ${error instanceof Error ? error.message : String(error)}`,
        );
      }
    }
    rmSync(mount, { recursive: true, force: true });
  }
}

try {
  console.log(`check-mac-icon: ${check()}`);
} catch (error) {
  console.error(`check-mac-icon: ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
}
