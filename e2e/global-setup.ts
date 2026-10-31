// The suite's backend lifecycle (roadmap item 21): one real backend on a fresh temporary
// STT_DATA_DIR for the whole run. globalSetup runs before the tests; the returned
// function is the teardown, which Playwright calls after the run (it also runs when the
// suite fails, so a stray backend cannot survive a broken run).
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  checkPreconditions,
  choosePort,
  loadState,
  repoRoot,
  saveState,
  startBackend,
  stopBackend,
} from './lib/backend.ts';
import { writeFixtures } from './lib/fixtures.ts';
import { buildUi } from './lib/ui-build.ts';
import { ensureUiBuilt } from './lib/ui-dist.ts';

export default async function globalSetup(): Promise<() => Promise<void>> {
  checkPreconditions();
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
  saveState({ port, dataDir, fixturesDir });
  const { pid } = await startBackend({ port, dataDir });
  saveState({ pid });
  console.log(`e2e: backend up on http://127.0.0.1:${port} (data dir ${dataDir})`);

  return async () => {
    const state = loadState();
    await stopBackend(state.pid, state.port, state.dataDir);
    rmSync(dataDir, { recursive: true, force: true });
    rmSync(fixturesDir, { recursive: true, force: true });
    console.log('e2e: backend stopped, data dir and fixtures removed');
  };
}
