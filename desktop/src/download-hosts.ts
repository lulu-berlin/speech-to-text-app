// Model provisioning's download rules (ADR 0055, the user 2026-10-15: "we should limit network
// access to the only domain where it gets downloaded from"): which hosts a pin may reach, and how a
// pinned download is fetched. Measured 2026-10-15 by following every pin's redirect chain:
// `github.com` serves both model assets and redirects to `release-assets.githubusercontent.com`;
// loopback stays allowed so a local mirror, and the deterministic test fixture, keep working. The
// hosts are exact: no other githubusercontent.com host (`raw.`, `objects.`) is allowed, so a CDN
// move fails here and in the weekly check-model-hosts job instead of being followed. A host outside
// this list is refused *before* a request is made, and the error names it — the caller (models.ts)
// only streams and verifies what this module hands it.
const ALLOWED_HOSTS = new Set([
  'github.com',
  'release-assets.githubusercontent.com',
  '127.0.0.1',
  '[::1]',
  'localhost',
]);

export function downloadHostAllowed(url: string): boolean {
  return ALLOWED_HOSTS.has(new URL(url).hostname);
}

// How many redirects a pin may follow; GitHub's chain is one hop today
// (`github.com` → `release-assets.githubusercontent.com`).
const REDIRECT_LIMIT = 5;

/**
 * The response for a pinned download, following redirects *by hand* so every hop is checked against
 * the allowlist above (fetch's automatic following would hide the hops). A refused host is named in
 * the error; a `3xx` without a `Location` is returned as-is, so the caller reports the status.
 */
export async function openPinnedDownload(url: string): Promise<Response> {
  let current = url;
  for (let hop = 0; hop <= REDIRECT_LIMIT; hop += 1) {
    if (!downloadHostAllowed(current)) {
      throw new Error(`${new URL(current).hostname} is not an allowed model host (${current})`);
    }
    const response = await fetch(current, { redirect: 'manual' });
    const location = response.headers.get('location');
    if (response.status >= 300 && response.status < 400 && location) {
      current = new URL(location, current).href;
      continue;
    }
    return response;
  }
  throw new Error(`${url} redirected more than ${REDIRECT_LIMIT} times`);
}
