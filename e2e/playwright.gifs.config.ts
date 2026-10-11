import { defineConfig } from '@playwright/test';

// The GIF source-video recorder for the README (roadmap item 35, test list item 6).
// Run standalone — `pnpm record:gifs` — against its own fresh backend from the same
// globalSetup; the ordinary suite never runs it (playwright.config.ts ignores it), and a
// standalone backend means the recordings can reuse the journey's fixtures without
// tripping the per-run content dedup. Settings mirror playwright.config.ts on purpose;
// the timeout is longer because the flows transcribe real audio end to end.
export default defineConfig({
  testDir: './tests',
  testMatch: '**/gifs-record.spec.ts',
  globalSetup: './global-setup.mjs',
  workers: 1,
  reporter: 'list',
  timeout: 900_000,
  expect: { timeout: 20_000 },
  use: {
    channel: 'chromium',
    viewport: { width: 1280, height: 800 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
