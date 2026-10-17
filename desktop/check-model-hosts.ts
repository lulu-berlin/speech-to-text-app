// Checks that the pinned model downloads still resolve through the hosts the app allows (ADR 0055):
// a provider moving its CDN is then caught before a user's first run hits it. Weekly in CI
// (`.github/workflows/ci.yml`, gated on the Monday cron); by hand with
// `pnpm --dir desktop check:model-hosts`. No model body is transferred — a ranged GET asks for one
// byte — and every redirect hop is checked before it is followed, using the same allowlist the app
// uses (src/download-hosts.ts), so the two cannot drift.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { downloadHostAllowed } from './src/download-hosts.ts';

// Only the two models the app itself provisions. `whisperTinyEn` is a Nix-side integration fixture
// (a store path in the devenv shell), which no user's download path touches.
const PROVISIONED = ['whisper', 'speaker'] as const;
// One more than the app's own limit, so a chain that the app would refuse is still reported here.
const REDIRECT_LIMIT = 5;

// versions.json is generated (the flake's render, guarded by `checks.versions-sync`), so this reads
// the one field it needs and validates it as it goes.
function modelUrls(raw: unknown): Record<string, string | undefined> {
  if (typeof raw !== 'object' || raw === null || !('models' in raw)) {
    throw new Error('versions.json has no models section');
  }
  const { models } = raw;
  if (typeof models !== 'object' || models === null) {
    throw new Error('versions.json models is not an object');
  }
  const urls: Record<string, string | undefined> = {};
  for (const [key, value] of Object.entries(models)) {
    if (
      typeof value === 'object' &&
      value !== null &&
      'url' in value &&
      typeof value.url === 'string'
    ) {
      urls[key] = value.url;
    }
  }
  return urls;
}

const here = dirname(fileURLToPath(import.meta.url));
const raw: unknown = JSON.parse(readFileSync(join(here, '..', 'versions.json'), 'utf8'));
const urls = modelUrls(raw);

let failed = false;
for (const key of PROVISIONED) {
  const pin = urls[key];
  if (!pin) {
    console.error(`${key}: versions.json has no url for models.${key}`);
    failed = true;
    continue;
  }
  const hops: string[] = [];
  let refused = false;
  let url = pin;
  let status = 0;
  let range = '';
  for (let hop = 0; hop <= REDIRECT_LIMIT; hop += 1) {
    const { hostname, pathname } = new URL(url);
    if (!downloadHostAllowed(url)) {
      console.error(`${key}: ${hostname} is not an allowed model host — ${url}`);
      failed = true;
      refused = true;
      break;
    }
    const response = await fetch(url, { redirect: 'manual', headers: { range: 'bytes=0-0' } });
    status = response.status;
    range = response.headers.get('content-range') ?? '';
    hops.push(`${status} ${hostname}${pathname}`);
    const location = response.headers.get('location');
    if (status >= 300 && status < 400 && location) {
      url = new URL(location, url).href;
      continue;
    }
    break;
  }
  if (refused) continue;
  const ok = status === 200 || status === 206;
  if (!ok) failed = true;
  console.log(
    `${key}: ${ok ? 'ok' : 'FAILED'} — ${hops.join(' -> ')}${range ? ` (${range})` : ''}`,
  );
}

if (failed) {
  console.error('the pinned model downloads no longer resolve the way the app expects');
  process.exit(1);
}
console.log('model hosts: both pins resolve through the allowlist');
