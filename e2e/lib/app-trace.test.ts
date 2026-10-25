// Roadmap item 82, rework: the app trace's close wrapper must not ask Playwright for the process
// after the app has exited — a quit spec closes the app in its `finally`, and `app.process()`
// throws then (runs 39540019816, 39891003455, 40156465546). The handle is taken once, while the
// app exists; an exited app leaves no trace to stop.
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { ElectronApplication, TestInfo } from '@playwright/test';
import { recordAppTrace } from '../desktop-tests/helpers/app.ts';

test('closing an app that already exited stops no trace and does not throw', async () => {
  let exited = false;
  let processCalls = 0;
  let traceStops = 0;
  let closed = false;
  // The live child process object Playwright returns at launch: its exit fields change when the
  // app exits, and asking Playwright for it again after that throws.
  const appProcess: { exitCode: number | null; signalCode: NodeJS.Signals | null } = {
    exitCode: null,
    signalCode: null,
  };
  const app = {
    context: () => ({
      tracing: {
        start: async () => {},
        stop: async () => {
          traceStops += 1;
        },
      },
    }),
    process: () => {
      processCalls += 1;
      if (exited) {
        throw new Error('This ElectronApplication has been closed');
      }
      return appProcess;
    },
    close: async () => {
      closed = true;
    },
  } as unknown as ElectronApplication;
  const testInfo = {
    title: 'a quit spec',
    attachments: [],
    outputPath: (name: string) => join(tmpdir(), name),
  } as unknown as TestInfo;

  await recordAppTrace(app, testInfo);
  exited = true; // the quit spec: the app is gone before its finally calls close()
  appProcess.exitCode = 0;
  await app.close();

  assert.equal(processCalls, 1, 'the process is read once, while the app exists');
  assert.equal(traceStops, 0, 'an exited app leaves nothing left to save');
  assert.equal(closed, true, 'the original close still runs');
});
