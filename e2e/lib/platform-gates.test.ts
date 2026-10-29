// Roadmap item 79, test-list item 4 (gating): every spec that cannot run on a platform is skipped
// with a reason the run shows, never silently. The check reads the journey's own sources, so a new
// silent skip fails here too.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { specSkips } from './platform-gates.ts';

const specDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'tests');

test('a spec that cannot run on a platform is skipped with a reason the run shows', () => {
  const specs = readdirSync(specDir)
    .filter((name) => name.endsWith('.spec.ts'))
    .map((name) => ({ name, source: readFileSync(join(specDir, name), 'utf8') }));

  // The audit of the journey's gating: printed, so a newly gated spec shows up in the run's output.
  const skips = specSkips(specs);
  console.log(`platform skips: ${JSON.stringify(skips)}`);
  assert.ok(
    skips.every(({ reason }) => reason.trim().length > 0),
    'every skip names a reason the run shows',
  );

  // A skip that names nothing hides the spec from the run — what this check exists to stop.
  assert.throws(
    () =>
      specSkips([{ name: 'silent.spec.ts', source: "test.skip(process.platform !== 'win32');" }]),
    /silent\.spec\.ts: a skip without a reason/,
  );
});
