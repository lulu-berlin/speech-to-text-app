// Downloads and extracts the pinned Temurin JDK (versions.json) into desktop/dist/jdk,
// whose jmods stage.ts links with jlink. sha256-verified like every tool download in this
// repository (verified-download.ts, ./gradlew prepareRuntime).
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { downloadVerified, pin } from './verified-download.ts';

const desktopRoot = dirname(fileURLToPath(import.meta.url));
const targetDir = join(desktopRoot, 'dist', 'jdk');

const fail = (message: string): never => {
  console.error(`fetch:jdk: ${message}`);
  process.exit(1);
};

// The cache name is the one this script has always used, so an existing entry in
// dist/downloads is reused instead of re-downloading the ~200 MB archive. Windows is a zip
// (Adoptium publishes no tar.gz there); the runner's bsdtar extracts it.
const windows = process.platform === 'win32';
const { sha256 } = pin('temurin-jdk', 'fetch:jdk');
const archive = downloadVerified({
  key: 'temurin-jdk',
  label: 'fetch:jdk',
  cacheName: `temurin-${sha256.slice(0, 12)}.${windows ? 'zip' : 'tar.gz'}`,
});

rmSync(targetDir, { recursive: true, force: true });
mkdirSync(targetDir, { recursive: true });
const strip = process.platform === 'darwin' ? 3 : 1;
const result = spawnSync(
  'tar',
  [windows ? '-xf' : '-xzf', archive, '-C', targetDir, `--strip-components=${strip}`],
  { stdio: 'inherit' },
);
if (result.status !== 0) fail('extraction failed');
if (!existsSync(join(targetDir, 'jmods'))) fail('extracted JDK has no jmods');
console.log('fetch:jdk: Temurin JDK ready at desktop/dist/jdk');
