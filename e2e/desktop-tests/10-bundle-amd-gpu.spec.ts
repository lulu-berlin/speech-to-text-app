import { test } from '@playwright/test';
import { launchDesktopApp, transcribeOnGpu } from './helpers/app';

// Roadmap item 50, approved item (bundle, AMD — the user's machine): in an Ubuntu container with
// only the host's /dev/dri passed in and no Mesa installed there, the bundle's own Vulkan driver
// (RADV) makes the AMD GPU usable: the probe reports it and a transcription on it completes
// without the CPU fallback.
test('the bundle finds the AMD GPU and transcribes on it', async () => {
  test.skip(
    process.env.STT_DESKTOP_GPU !== 'amd',
    'needs the host AMD GPU in the container: pnpm --dir e2e test:desktop:bundle:amd',
  );
  const app = await launchDesktopApp();
  try {
    await transcribeOnGpu(await app.firstWindow(), /RADV/);
  } finally {
    await app.close();
  }
});
