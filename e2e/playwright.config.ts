import { defineConfig } from '@playwright/test';

// The e2e suite (roadmap item 21) runs the real backend on a fresh temporary data dir
// (global-setup.ts) and drives the built app served by that backend. One worker: the
// suite is one journey against one shared backend; sequential tests keep the uploaded
// state unambiguous.
export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.ts',
  // Item 35's GIF recorder is not part of the journey: it runs standalone via
  // `pnpm record:gifs` (playwright.gifs.config.ts) against its own fresh backend.
  testIgnore: ['**/gifs-record.spec.ts'],
  workers: 1,
  // The JSON report carries every spec's duration; CI attaches it from the Linux and Windows
  // journeys so their per-spec times can be compared (roadmap item 84). playwright-report/ is
  // ignored and outside test-results/, which Playwright clears and the failure upload keeps.
  reporter: [['list'], ['json', { outputFile: 'playwright-report/journey.json' }]],
  // The outer safety net only, not a per-spec budget (roadmap item 84, load): specs wait on the
  // task's own state, and a stalled queue ends that wait in ten minutes. A loaded runner can make
  // a transcription take several times its usual time (CI run #435 ran the journey in 45.8 min
  // against 12), and the old 240–600 s per-spec ceilings redded specs that were merely slow.
  timeout: 1_200_000,
  expect: { timeout: 20_000 },
  use: {
    // The farm's chromium-headless-shell renderer hits a Blink NOTREACHED crash on the
    // app's self-hosted WOFF2 (font-face loading) — the full chromium build is unaffected.
    // Decided: found while wiring up this suite; pinned here so the tests run the stable
    // binary headlessly.
    channel: 'chromium',
    viewport: { width: 1280, height: 800 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
