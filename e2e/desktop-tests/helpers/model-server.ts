import { once } from 'node:events';
import { createReadStream, mkdtempSync, writeFileSync } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { required } from './app';

// A loopback http server that stands in for the model host (roadmap item 50, test item 3): the
// spec points the app's model pins at it (STT_MODELS_FILE), so the real download path —
// streaming, progress, sha256 verification, cache reuse — runs offline and deterministically.
// A file the server serves; `corruptFirst` makes its *first* response carry one flipped byte, so
// the app's sha256 check must reject it (item 8) and the second response can succeed;
// `redirectFirst` answers its first request with a 302 to `<name>.redirected`, so the spec
// exercises the app's own redirect following (ADR 0055: every hop is checked against the host
// allowlist, which only the app itself can verify).
export interface ModelFile {
  name: string;
  path: string;
  corruptFirst?: boolean;
  redirectFirst?: boolean;
}
export interface ModelServer {
  baseUrl: string;
  /** How many model responses it has answered; cache reuse means this stops growing. */
  requestCount(): number;
  /** Lets responses held by `hold` go: a spec asserts the download screen while they hang. */
  release(): void;
  close(): Promise<void>;
}

export interface ModelServerOptions {
  /**
   * Hold every response until `release()`: item 3's spec asserts the download screen while the app
   * waits. Off by default so a spec that just wants the downloads to run need not know about it.
   */
  hold?: boolean;
}

export async function startModelServer(
  files: ModelFile[],
  options: ModelServerOptions = {},
): Promise<ModelServer> {
  let requests = 0;
  let released = false;
  const served: Record<string, number> = {};
  const waiting: (() => void)[] = [];
  const server = createServer(async (request, response) => {
    const file = files.find(
      (candidate) =>
        request.url === `/${candidate.name}` ||
        (candidate.redirectFirst && request.url === `/${candidate.name}.redirected`),
    );
    if (!file) {
      response.writeHead(404);
      response.end();
      return;
    }
    requests += 1;
    const count = (served[file.name] ?? 0) + 1;
    served[file.name] = count;
    if (options.hold && !released) {
      const holding = Promise.withResolvers<void>();
      waiting.push(holding.resolve);
      await holding.promise;
    }
    if (file.redirectFirst && count === 1) {
      response.writeHead(302, { location: `/${file.name}.redirected` });
      response.end();
      return;
    }
    if (file.corruptFirst && count === 1) {
      const bytes = await readFile(file.path);
      bytes[0] = (bytes[0] ?? 0) ^ 0xff;
      response.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': bytes.length,
      });
      response.end(bytes);
      return;
    }
    const { size } = await stat(file.path);
    response.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': size,
    });
    createReadStream(file.path).pipe(response);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const { port } = server.address() as AddressInfo;
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    requestCount: () => requests,
    release: () => {
      released = true;
      for (const resolve of waiting.splice(0)) resolve();
    },
    // Roadmap item 84: `server.close()` waits for every open connection, and an app that still
    // holds one (keep-alive, a download it gave up on, a response still held) kept the packaged
    // spec's teardown waiting with no end. Held responses go and every connection is cut first.
    close: () => {
      released = true;
      for (const resolve of waiting.splice(0)) resolve();
      server.closeAllConnections();
      const closed = Promise.withResolvers<void>();
      server.close((error) => (error ? closed.reject(error) : closed.resolve()));
      return closed.promise;
    },
  };
}

/** The file names the pins put in their URLs — the name each model is cached (and looked up) by. */
export interface PinnedNames {
  whisper?: string;
  speaker?: string;
}

/**
 * The pins file a spec points the app at (STT_MODELS_FILE): the shape versions.json has, with this
 * server's local URLs for the two fixture models and the *repository's* sha256 for them (the harness
 * passes those as TEST_*_SHA256). Written per call so each spec gets its own server's URL. The file
 * names default to the models' store basenames (hash prefix included), which no Nix store path
 * computed from a pin can match — so by default the app downloads.
 */
export function writeFixturePins(baseUrl: string, names: PinnedNames = {}): string {
  const {
    whisper = basename(required('TEST_WHISPER_TINY_EN_MODEL')),
    speaker = basename(required('STT_SPEAKER_MODEL')),
  } = names;
  const fixture = {
    models: {
      whisper: { url: `${baseUrl}/${whisper}`, sha256: required('TEST_WHISPER_TINY_EN_SHA256') },
      speaker: { url: `${baseUrl}/${speaker}`, sha256: required('TEST_SPEAKER_SHA256') },
    },
  };
  const file = join(mkdtempSync(join(tmpdir(), 'stt-desktop-pins-')), 'models.json');
  writeFileSync(file, JSON.stringify(fixture));
  return file;
}
