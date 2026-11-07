// The journey's shared `test` (roadmap item 84, discovered, load): CI run #452 failed two Windows
// specs because earlier specs left clips queued and running, so a spec's own task waited behind
// them. Every journey spec imports `test` and `expect` from here, and an automatic fixture resets
// the shared backend's queue before each spec through the API: every pending task canceled, a paused
// queue resumed, and the spec starts once nothing is pending (e2e/lib/reset-queue.ts).
import {
  test as base,
  expect,
  type APIRequestContext,
  type Locator,
  type Page,
} from '@playwright/test';
import type { TaskSnapshot } from '../../lib/await-task.ts';
import { appUrl } from '../../lib/backend.ts';
import { BackendUnanswered, resetQueue } from '../../lib/reset-queue.ts';
import {
  SLOW_RUNNER_FACTOR,
  transcriptionRatio,
  type SpecTiming,
} from '../../lib/runner-health.ts';

// The journey's specs, timed as they finish, for the runner-health guard below.
const specTimings: SpecTiming[] = [];

// A request that never gets an answer is BackendUnanswered (the reset steps aside); an answer that
// is not a 2xx JSON body fails with the request and what came back.
async function getJson(request: APIRequestContext, path: string): Promise<Record<string, unknown>> {
  let response;
  try {
    response = await request.get(appUrl(path));
  } catch (error) {
    throw new BackendUnanswered(
      `GET ${path}: ${error instanceof Error ? error.message : String(error)}`,
      {
        cause: error,
      },
    );
  }
  const text = await response.text();
  if (!response.ok()) throw new Error(`GET ${path} answered ${response.status()}: ${text}`);
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    throw new Error(
      `GET ${path} answered ${response.status()} with a body that is not JSON: ${text}`,
    );
  }
}

export const test = base.extend<{ queueReset: void; runnerHealth: void }>({
  // Roadmap item 85 (discovered): a pathological runner is recognised by its own spec timings and
  // the journey aborts, instead of sitting out the job's timeout (runs #544, #570, #576).
  runnerHealth: [
    async ({}, use, testInfo) => {
      await use();
      specTimings.push({ file: testInfo.file, ms: testInfo.duration });
      const ratio = transcriptionRatio(specTimings);
      if (ratio === null || ratio.factor <= SLOW_RUNNER_FACTOR) return;
      throw new Error(
        `the runner transcribes ${ratio.factor.toFixed(1)}× slower than it drives the UI ` +
          `(03-upload-queue ${Math.round(ratio.transcriptionMs / 1000)} s against 01+02+04+05 ` +
          `${Math.round(ratio.uiMs / 1000)} s); healthy runs measure 0.7–2.4×, the 64-minute VM of ` +
          `CI run #544 measured 4.7× and run #605's 24.7× — re-run the job instead of waiting out ` +
          `its timeout`,
      );
    },
    { auto: true },
  ],
  queueReset: [
    async ({ request }, use) => {
      await resetQueue({
        snapshot: async () => {
          const list = await getJson(request, '/api/tasks');
          const queue = await getJson(request, '/api/queue');
          if (!Array.isArray(list.tasks)) {
            throw new Error(
              `GET /api/tasks answered without a "tasks" array: ${JSON.stringify(list)}`,
            );
          }
          if (typeof queue.paused !== 'boolean') {
            throw new Error(
              `GET /api/queue answered without a boolean "paused": ${JSON.stringify(queue)}`,
            );
          }
          return { tasks: list.tasks as TaskSnapshot[], paused: queue.paused };
        },
        cancel: async (id) => {
          const response = await request.post(appUrl(`/api/tasks/${id}/cancel`));
          // 409: the task ended between the snapshot and the cancel; the next snapshot shows it.
          if (!response.ok() && response.status() !== 409) {
            throw new Error(`canceling task ${id} answered ${response.status()}`);
          }
        },
        resume: async () => {
          const response = await request.post(appUrl('/api/queue/resume'));
          if (!response.ok()) throw new Error(`resuming the queue answered ${response.status()}`);
        },
      });
      await use();
    },
    { auto: true },
  ],
});

export { expect };
export type { Locator, Page };
