// The suite's backend lifecycle (roadmap item 21): one real backend on a fresh temporary
// STT_DATA_DIR for the whole run. globalSetup runs before the tests; the returned
// function is the teardown, which Playwright calls after the run (it also runs when the
// suite fails, so a stray backend cannot survive a broken run).
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  appModeDirs,
  captureSessionCookie,
  checkPreconditions,
  choosePort,
  STORAGE_STATE_FILE,
  loadState,
  refuseLiveRun,
  registerBrowserFrontend,
  repoRoot,
  saveState,
  startBackend,
  stopBackend,
  writeStoredSession,
} from './lib/backend.ts';
import { writeFixtures } from './lib/fixtures.ts';
import { buildUi } from './lib/ui-build.ts';
import { ensureUiBuilt } from './lib/ui-dist.ts';
import { removeWithRetry } from './lib/cleanup.ts';

export default async function globalSetup(): Promise<() => Promise<void>> {
  checkPreconditions();
  // Roadmap item 84: a second journey from the same checkout would start a backend over the first
  // one's and both would fail as a cascade, so a live run is refused before anything starts.
  refuseLiveRun();
  // Roadmap item 84: the journey serves a UI built from this checkout — rebuilt when a build
  // input changed since the last build, and reused when nothing did.
  await ensureUiBuilt({
    root: repoRoot,
    build: () => buildUi({ root: repoRoot }),
    log: console.log,
  });
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-'));
  const fixturesDir = mkdtempSync(join(tmpdir(), 'stt-e2e-fixtures-'));
  writeFixtures(fixturesDir);
  const port = await choosePort();
  const { configDir, runtimeDir } = appModeDirs(dataDir);
  saveState({ port, dataDir, fixturesDir, configDir, runtimeDir });
  const { pid } = await startBackend({ port, dataDir });
  saveState({ pid });
  // The journey's browser enters through the service's launch handshake (roadmap item 66, line E2):
  // the cookie is captured once here and every test context starts with it. `E2E_BACKEND_CMD` names a
  // dev-mode server instead (the macOS and Windows jobs run the app that way), which has no launch
  // handshake and no per-user config directory.
  if (process.env.E2E_BACKEND_CMD === undefined) {
    registerBrowserFrontend(configDir);
    writeStoredSession(await captureSessionCookie({ configDir, runtimeDir }));
  }
  console.log(`e2e: backend up on http://127.0.0.1:${port} (data dir ${dataDir})`);

  return async () => {
    const state = loadState();
    await stopBackend(state.pid, state.port, state.dataDir);
    // Windows can still hold a handle on a stopped backend's data directory for a moment after the
    // port closes, and a fully green suite must not fail in its own teardown (CI run #475: 55
    // passed, 1 skipped, and the run was red on an EPERM here). The removal is retried; a
    // directory that never goes away is reported, not thrown.
    for (const dir of [dataDir, fixturesDir]) {
      if (!(await removeWithRetry(dir))) {
        console.warn(`e2e: the temporary directory ${dir} could not be removed`);
      }
    }
    console.log('e2e: backend stopped, data dir and fixtures removed');
  };
}
