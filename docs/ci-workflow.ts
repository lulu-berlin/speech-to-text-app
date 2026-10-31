// The jobs and steps of `.github/workflows/ci.yml` that the CI checks read (roadmap items 80 and
// 84). The file is parsed rather than loaded with a YAML library because the repository has none
// and these checks must run with only the Node the devenv pins.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { repoRoot } from './run-check.ts';

/** The workflow the checks read. */
export const workflowPath = join(repoRoot, '.github/workflows/ci.yml');

export interface Job {
  id: string;
  name: string | null;
  needs: string[];
  if: string | null;
  raw: string[];
}

// The job ids of the workflow, with the job-level keys the checks read; `raw` holds the job's
// lines below its id, steps included.
export function parseJobs(text: string): Map<string, Job> {
  const jobs = new Map<string, Job>();
  let inJobs = false;
  let current: Job | null = null;
  let blockIndent = -1;
  for (const line of text.split('\n')) {
    const indent = line.length - line.trimStart().length;
    if (blockIndent >= 0) {
      if (line.trim() === '' || indent > blockIndent) continue;
      blockIndent = -1;
    }
    if (/^jobs:\s*$/.test(line)) {
      inJobs = true;
      continue;
    }
    if (!inJobs) continue;
    const job = /^ {2}([A-Za-z0-9_-]+):\s*$/.exec(line);
    if (job) {
      current = { id: job[1] ?? '', name: null, needs: [], if: null, raw: [] };
      jobs.set(current.id, current);
      continue;
    }
    if (current === null) continue;
    current.raw.push(line);
    if (indent !== 4) continue;
    const key = /^ {4}([A-Za-z0-9_-]+):(.*)$/.exec(line);
    if (key === null) continue;
    const [, name = '', rest = ''] = key;
    const value = rest.trim().replace(/^(['"])(.*)\1$/, '$2');
    if (/^[|>]/.test(value)) {
      blockIndent = 4;
      continue;
    }
    if (name === 'name') current.name = value;
    if (name === 'if') current.if = value;
    if (name === 'needs' && value.startsWith('[')) {
      current.needs = value
        .slice(1, -1)
        .split(',')
        .map((need) => need.trim())
        .filter(Boolean);
    }
  }
  return jobs;
}

// The job [id] the assertions read; a workflow that lost it fails by name instead of as a read of
// undefined.
export function assertJobOf(jobs: Map<string, Job>, id: string): Job {
  const job = jobs.get(id);
  assert.ok(job, `${id} is a job`);
  return job;
}

// The steps of [job], each as its lines joined: a step starts at a `- ` item under `steps:`.
export function stepsOf(job: Job): string[] {
  const steps: string[][] = [];
  for (const line of job.raw) {
    if (/^ {6}- /.test(line)) steps.push([]);
    steps.at(-1)?.push(line);
  }
  return steps.map((lines) => lines.join('\n'));
}
