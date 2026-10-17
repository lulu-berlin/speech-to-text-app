# 0055 — The desktop window has no network path: its own file and the loopback backend, nothing else

Status: accepted · Date: 2026-10-15

## Context

The app transcribes on the machine and is sold on that: a recording and its transcript never leave
it. The code already behaved that way — the only external URLs anywhere in the frontend are SVG
`xmlns` namespaces, fonts are self-hosted, the backend binds loopback and shells out to local
`whisper-cli`/`ffmpeg` — but nothing *enforced* it. `desktop/src` had no request filter, no
permission handler, no popup or navigation guard, the backend serves no CSP, and Chromium's spell
checker would fetch dictionaries from the network on its own. "No call leaves the machine" rested
on the absence of code that makes one.

The one intended outbound call is the model download, and it does not go through the window: it is
a `fetch` in the Electron main process (`desktop/src/models.ts`), whose URLs are pinned in
`versions.json` and whose content is verified against a sha256. The user raised this on 2026-10-15:
"There's no reason for it to make any call to the internet, and that's a feature."

## Decision

- **The window may reach its own file and the loopback backend only.** `session.webRequest
  .onBeforeRequest` cancels every request outside that allowlist (`file:`, `data:`, `blob:`, and
  `http(s)` to `127.0.0.1`, `[::1]` or `localhost`), in `desktop/src/main.ts`.
- **No permission is granted**: `setPermissionRequestHandler` and `setPermissionCheckHandler` both
  deny, so a *request* and a *query* (`navigator.permissions.query`) agree.
- **No popups and no navigating away**: `setWindowOpenHandler` denies, and a `will-frame-navigate`
  guard refuses any URL outside the allowlist — before the request is made, so the frame never
  leaves the app. (Electron 44 deprecates `will-navigate`'s extra arguments in favour of this
  event, and neither fires for main-process `loadURL`, so the progress page → backend URL step is
  untouched.) A `did-fail-load` handler is the safety net: if a blocked navigation ever did leave
  the main frame empty, the app reloads its own URL.
- **Provisioning is separately allowlisted.** The main-process model fetch follows redirects
  manually, permitting only `github.com` and `release-assets.githubusercontent.com` (plus loopback
  for the deterministic test fixture/local mirror); each hop is checked before following it, and
  the final body still has to match its pinned sha256. Both pinned models come from GitHub Releases:
  the whisper weights from this repository's `desktop-models-v1` release — a byte-identical mirror
  of the upstream file, same sha256 and size, uploaded by the human (ADR 0008's update, ADR 0050) —
  and the CAM++ model from the `k2-fsa/sherpa-onnx` release. Tool downloads at build time are not
  app network access and are outside this window/provisioning policy.

## Consequences

- A recording or a transcript cannot leave the machine *by construction* rather than by absence of
  code, and a regression that reintroduces a request fails the suite instead of shipping.
- What can still use the network: the main-process fetches for the two pinned, sha256-verified model
  files, through their allowlisted GitHub release URLs and redirect host; all other window traffic
  is blocked. The host's own tools (`whisper-cli`, `ffmpeg`) run locally. Any future feature that
  needs the network has to amend this ADR.
- The bundle inherits the same policy, since it runs the same main process.
- Verified by `e2e/desktop-tests/07-network-policy.spec.ts`, which starts one stand-in server on
  **two** addresses and asserts that loopback answers while the machine's own non-loopback address
  is refused — an offline machine would fail both and a permissive app neither — plus popups,
  the permission query, and navigation.
- A weekly CI job (`desktop/check-model-hosts.ts`, gated on the Monday cron, deliberately outside
  the required `ci` aggregator) walks both pins' redirect chains with ranged GETs — the same
  allowlist, imported from `desktop/src/download-hosts.ts` — and fails naming the hop that left it,
  so a provider moving its CDN is caught before a user's first run is.
