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
  supportedPlatforms?: string[];
  downloads: Record<string, Record<string, Pin> | undefined>;
};

const supportedPlatforms = manifest.supportedPlatforms ?? [];

const fail = (label: string, message: string): never => {
  console.error(`${label}: ${message}`);
  process.exit(1);
};

/**
 * This host's Nix system name (roadmap item 90), the manifest's platform vocabulary. The OS/arch
 * names stay here, translated once; Intel macOS, Windows ARM64 and Linux ARM64 resolve to names the
 * supported list does not hold, so `pin` refuses them before any download.
 */
export function platformKey(
  platformName: NodeJS.Platform = process.platform,
  arch: string = process.arch,
): string {
  if (platformName === 'darwin') return arch === 'arm64' ? 'aarch64-darwin' : 'x86_64-darwin';
  if (platformName === 'win32') return arch === 'arm64' ? 'aarch64-windows' : 'x86_64-windows';
  if (platformName === 'linux') return arch === 'arm64' ? 'aarch64-linux' : 'x86_64-linux';
  return `${platformName}-${arch}`;
}

/** The `{ url, sha256 }` pin of `key` in versions.json for this build host's platform. */
export function pin(key: string, label: string, platform = platformKey()): Pin {
  if (!supportedPlatforms.includes(platform)) {
    fail(label, `unsupported platform ${platform} (supported: ${supportedPlatforms.join(', ')})`);
  }
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
