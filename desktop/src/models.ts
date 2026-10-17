// Model provisioning for the desktop app (roadmap item 50, test item 3): both pinned models must
// be in the per-user cache, sha256-verified, before the backend starts. The pins are the `models`
// section of the flake-rendered versions.json (`nix/models.nix` is the source, the flake's
// versions-sync check guards drift); STT_MODELS_FILE overrides the file — the packaged e2e points
// it at fixture pins, so no test downloads the ~570 MB production model.
//
// Nothing here touches Electron: main.ts drives it and relays `onProgress` to the download
// screen, so a plain Node script can exercise the whole provisioning path. Which hosts that path
// may reach is in download-hosts.ts (ADR 0055).
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readFile, rename, rm, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { Readable, Transform, type TransformCallback } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { openPinnedDownload } from './download-hosts.js';

const MODEL_KEYS = ['whisper', 'speaker'] as const;
type ModelKey = (typeof MODEL_KEYS)[number];

/** The model files the backend gets, as `STT_WHISPER_MODEL` / `STT_SPEAKER_MODEL`. */
export type ModelPaths = Record<ModelKey, string>;

interface ModelPin {
  key: ModelKey;
  file: string;
  url: string;
  sha256: string;
}

type OnProgress = (progress: ModelProgress) => void;

// ${XDG_CACHE_HOME:-~/.cache}/stt-app/models (decided 2026-10-14): re-downloadable, so backups
// skip it, while the data directory beside it holds the transcripts.
export function modelsDir(): string {
  const base = process.env.XDG_CACHE_HOME || join(homedir(), '.cache');
  return join(base, 'stt-app', 'models');
}

// The environment variable a user sets to bring their own model (the Nix wrapper's rule: a
// user-set value wins over the download).
function overrideFor(key: ModelKey): string | undefined {
  return process.env[`STT_${key.toUpperCase()}_MODEL`];
}

/**
 * The paths of both models, downloading the ones that are missing into the per-user cache. A
 * model already in the Nix store at its pin's fixed-output path is used from there (ADR 0053);
 * a model already cached is re-verified and reused (never fetched again); a download that fails
 * its sha256 is discarded, so a corrupt file can never reach the backend. `onProgress` receives
 * `{ key, file, received, total }` while bytes arrive and once a stored or cached file is ready.
 */
export async function ensureModels({
  resourcesDir,
  onProgress = () => {},
}: {
  resourcesDir: string;
  onProgress?: OnProgress;
}): Promise<ModelPaths> {
  const paths: Partial<ModelPaths> = {};
  const missing: ModelKey[] = [];
  for (const key of MODEL_KEYS) {
    const override = overrideFor(key);
    if (override) paths[key] = override;
    else missing.push(key);
  }
  if (missing.length > 0) {
    const dir = modelsDir();
    await mkdir(dir, { recursive: true });
    for (const pin of await modelPins(resourcesDir, missing)) {
      paths[pin.key] = await locateModel(pin, dir, onProgress);
    }
  }
  // Every key is now set: by its override, or by the pin `missing` listed for it.
  return paths as ModelPaths;
}

// Where a pinned model comes from, first match wins: the Nix store (ADR 0053), the verified
// per-user cache, a download into that cache. Returns the path the backend gets.
async function locateModel(pin: ModelPin, dir: string, onProgress: OnProgress): Promise<string> {
  const inStore = fixedOutputStorePath(pin.sha256, pin.file);
  if (await isFile(inStore)) {
    onProgress({ key: pin.key, file: pin.file, received: 0, total: 0 });
    return inStore;
  }
  const cached = join(dir, pin.file);
  if (await isVerified(cached, pin.sha256)) {
    onProgress({ key: pin.key, file: pin.file, received: 0, total: 0 });
    return cached;
  }
  await download(pin, cached, onProgress);
  return cached;
}

// The pins of `keys`, in download order, the cache file name taken from the URL (the same rule
// stage.ts uses for the tools).
async function modelPins(resourcesDir: string, keys: ModelKey[]): Promise<ModelPin[]> {
  const file = process.env.STT_MODELS_FILE || join(resourcesDir, 'versions.json');
  const { models } = JSON.parse(await readFile(file, 'utf8')) as {
    models?: Partial<Record<ModelKey, { url?: string; sha256?: string }>>;
  };
  return keys.map((key) => {
    const pin = models?.[key];
    if (!pin?.url || !pin?.sha256) throw new Error(`${file} has no models.${key} pin`);
    const fileName = pin.url.slice(pin.url.lastIndexOf('/') + 1);
    return { key, file: fileName, url: pin.url, sha256: pin.sha256 };
  });
}

// Where Nix keeps a pin's file when something on this machine fetched it (`pkgs.fetchurl`, as
// nix/models.nix does — the flake package, the devenv shell): a flat sha256 fixed-output path is a
// function of the file name and the content hash alone, so it is computed here without Nix and
// without the package referencing the model (ADR 0053: the closure, and the bundle made from it,
// stay model-free). Checked 2026-10-15 against the real store paths of the three pinned models.
// Store paths are content-addressed and read-only, so a hit is not re-hashed.
function fixedOutputStorePath(sha256: string, name: string, storeDir = '/nix/store'): string {
  const sha = (text: string) => createHash('sha256').update(text).digest();
  const inner = sha(`fixed:out:sha256:${sha256}:`).toString('hex');
  const digest = sha(`output:out:sha256:${inner}:${storeDir}:${name}`);
  const folded = Buffer.alloc(20);
  digest.forEach((byte, i) => {
    folded[i % 20] = (folded[i % 20] ?? 0) ^ byte;
  });
  return `${storeDir}/${nixBase32(folded)}-${name}`;
}

// Nix's base32: its own alphabet, read from the last 5-bit group to the first.
function nixBase32(bytes: Buffer): string {
  const alphabet = '0123456789abcdfghijklmnpqrsvwxyz';
  let out = '';
  for (let n = Math.ceil((bytes.length * 8) / 5) - 1; n >= 0; n--) {
    const bit = n * 5;
    const i = bit >> 3;
    const j = bit & 7;
    const high = i + 1 < bytes.length ? (bytes[i + 1] ?? 0) << (8 - j) : 0;
    out += alphabet.charAt((((bytes[i] ?? 0) >> j) | high) & 0x1f);
  }
  return out;
}

async function isFile(path: string): Promise<boolean> {
  try {
    return (await stat(path)).isFile();
  } catch {
    return false; // no Nix, or the store does not hold this pin (never fetched, collected)
  }
}

async function isVerified(path: string, sha256: string): Promise<boolean> {
  try {
    return (await sha256Of(path)) === sha256;
  } catch {
    return false; // no such file (or unreadable): download it
  }
}

async function sha256Of(path: string): Promise<string> {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk as Buffer);
  return hash.digest('hex');
}

// Streams the pin into `<path>.part` (hashing and reporting as it goes), then renames it into
// place: the cache only ever holds complete, verified files.
async function download(pin: ModelPin, path: string, onProgress: OnProgress): Promise<void> {
  const partial = `${path}.part`;
  const response = await openPinnedDownload(pin.url);
  if (!response.ok || !response.body) throw new Error(`${pin.url} answered ${response.status}`);
  const total = Number(response.headers.get('content-length') ?? 0);
  const hash = createHash('sha256');
  let received = 0;
  const meter = new Transform({
    transform(chunk: Buffer, _encoding: BufferEncoding, done: TransformCallback) {
      hash.update(chunk);
      received += chunk.length;
      onProgress({ key: pin.key, file: pin.file, received, total: total || received });
      done(null, chunk);
    },
  });
  try {
    await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(partial));
  } catch (error) {
    await rm(partial, { force: true });
    throw error;
  }
  const actual = hash.digest('hex');
  if (actual !== pin.sha256) {
    await rm(partial, { force: true });
    throw new Error(`${pin.file} is ${actual}, ${pin.url} pins ${pin.sha256}`);
  }
  await rename(partial, path);
  onProgress({ key: pin.key, file: pin.file, received, total: total || received });
}
