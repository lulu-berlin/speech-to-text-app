import { test } from '@playwright/test';
import { launchDesktopApp, transcribeOnGpu } from './helpers/app';

// Roadmap item 50, approved item (bundle, NVIDIA's own driver — the user's machine): in an Ubuntu
// container with the host's /dev/nvidia* devices and NVIDIA's user-space driver of the host's
// version installed the way Ubuntu has it, the bundle borrows that driver — it must match the
// kernel module, so the bundle cannot ship it: the probe reports the NVIDIA GPU and a
// transcription on it completes without the CPU fallback.
test("the bundle finds the NVIDIA GPU through the host's driver and transcribes on it", async () => {
  test.skip(
    process.env.STT_DESKTOP_GPU !== 'nvidia',
    "needs the host's NVIDIA GPU and driver in the container: pnpm --dir e2e test:desktop:bundle:nvidia",
  );
  const app = await launchDesktopApp();
  try {
    await transcribeOnGpu(await app.firstWindow(), /NVIDIA/);
  } finally {
    await app.close();
  }
});
