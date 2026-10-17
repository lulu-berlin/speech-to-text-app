import { expect, test } from '@playwright/test';
import { existsSync, readFileSync, realpathSync } from 'node:fs';
import { launchDesktopApp, processes } from './helpers/app';

// Roadmap item 50 (ADR 0053), discovered item (the user, 2026-10-15): the bundle's app sees the
// whole machine like any installed app — a recording, a model or a download folder anywhere, with
// the user's own permissions — except /nix, which is the bundle's own store, read-only. A host's
// /nix (here: the container's store mount that carries the test tooling) is hidden.
//
// The namespace is read from outside through the running backend's /proc entries: its root shows
// the host's directories, and its mount table gives the effective mount at each path (the last
// line for a path wins: bubblewrap stacks its mounts on a scratch root).
test('the bundled app sees the whole machine, except /nix, which is its own store', async () => {
  test.skip(process.env.STT_DESKTOP_PACKAGE !== 'bundle', "the bundle's namespace only");
  const app = await launchDesktopApp();
  try {
    await app.firstWindow();
    await expect.poll(() => processes(/stt\.MainKt/).length, { timeout: 120_000 }).toBe(1);
    const [backend] = processes(/stt\.MainKt/);
    if (!backend) throw new Error('the backend disappeared');
    const root = `/proc/${backend.pid}/root`;

    // The host is visible: a directory none of the earlier binds carried.
    expect(existsSync(`${root}/usr/bin`)).toBe(true);
    // The container's store (this test's own node) is not: /nix is the bundle's.
    expect(existsSync(`${root}${realpathSync(process.execPath)}`)).toBe(false);

    const effective = new Map<string, string[]>();
    for (const line of readFileSync(`/proc/${backend.pid}/mountinfo`, 'utf8').trim().split('\n')) {
      const fields = line.split(' ');
      effective.set(fields[4] ?? '', (fields[5] ?? '').split(','));
    }
    // The host's directories keep their own permissions: mounted writable, where the user's
    // file modes then decide (the namespace's root itself is bubblewrap's scratch tmpfs, writable
    // either way, so it says nothing). /nix is the bundle's store, read-only.
    expect(effective.get('/usr')).toContain('rw');
    expect(effective.get('/nix')).toContain('ro');
  } finally {
    await app.close();
  }
});
