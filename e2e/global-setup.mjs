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
  saveState,
  startBackend,
  stopBackend,
} from './lib/backend.mjs';

export default async function globalSetup() {
  checkPreconditions();
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-'));
  const port = await choosePort();
  saveState({ port, dataDir });
  const { pid } = await startBackend({ port, dataDir });
  saveState({ pid });
  console.log(`e2e: backend up on http://127.0.0.1:${port} (data dir ${dataDir})`);

  return async () => {
    const state = loadState();
    await stopBackend(state.pid, state.port);
    rmSync(dataDir, { recursive: true, force: true });
    console.log('e2e: backend stopped, data dir removed');
  };
}
