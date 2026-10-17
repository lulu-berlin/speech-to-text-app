// Downloads a pinned artifact from versions.json into desktop/dist/downloads and verifies it
// against the pinned sha256 — the same contract as ./gradlew prepareRuntime. Shared by
// stage.ts (whisper-cli, ffmpeg) and fetch-jdk.ts (the Temurin JDK), so the download, the
// cache reuse and the fail-loudly-on-mismatch rule exist once. Build-time only: it is not
// part of the packaged app (electron-builder.yml lists the app's files explicitly).
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export interface Pin {
  url: string;
  sha256: string;
}

const desktopRoot = dirname(fileURLToPath(import.meta.url));
const downloads = join(desktopRoot, 'dist', 'downloads');
const manifest = JSON.parse(readFileSync(join(desktopRoot, '..', 'versions.json'), 'utf8')) as {
  downloads: Record<string, Record<string, Pin> | undefined>;
};

const fail = (label: string, message: string): never => {
  console.error(`${label}: ${message}`);
  process.exit(1);
};

/** The `{ url, sha256 }` pin of `key` in versions.json for `platform`. */
export function pin(key: string, label: string, platform = 'linux-x64'): Pin {
  return (
    manifest.downloads[key]?.[platform] ?? fail(label, `versions.json has no ${platform} ${key}`)
  );
}

/**
 * Downloads `key`'s pinned artifact into dist/downloads as `cacheName`, once: an existing
 * file is reused (and re-verified), a missing one is fetched with curl. A sha256 that differs
 * from the pin is fatal — a corrupt cache must never be packaged or extracted.
 */
export function downloadVerified({
  key,
  label,
  cacheName,
}: {
  key: string;
  label: string;
  cacheName: string;
}): string {
  const { url, sha256 } = pin(key, label);
  const file = join(downloads, cacheName);
  mkdirSync(downloads, { recursive: true });
  if (existsSync(file)) {
    verify(file, sha256, label);
    console.log(`${label}: ${key} verified from cache (${file})`);
    return file;
  }
  console.log(`${label}: downloading ${url}`);
  const result = spawnSync('curl', ['-fsSL', url, '-o', file], { stdio: 'inherit' });
  if (result.status !== 0) fail(label, `download of ${url} failed`);
  verify(file, sha256, label);
  return file;
}

function verify(file: string, sha256: string, label: string): void {
  const actual = createHash('sha256').update(readFileSync(file)).digest('hex');
  if (actual !== sha256) fail(label, `${file} is ${actual}, versions.json pins ${sha256}`);
}
