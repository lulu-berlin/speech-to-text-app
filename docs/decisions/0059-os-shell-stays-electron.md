# 0059 — The Linux desktop app keeps Electron: Tauri 2's 24–32% saving is a cross-date comparison, the comparable unpacked saving is inferred at ~36%, and ADR 0055 cannot be kept

Status: accepted · Date: 2026-10-20

## Context

Roadmap item 64 asked for a measured go/no-go on replacing Electron with Tauri 2 on Linux, across
all three artifacts, with a working prototype of the existing UI and backend and a packaged e2e.
The prototype lived only on `roadmap/64-tauri-evaluation`, was never merged, and remains on that
branch as evidence; the branch is rebased onto `main`'s `52e99c3b`, and this item changed nothing on
`main`.
Every number below was produced by the branch's own checks on the user's machine on 2026-10-19/20,
next to item 50's Electron numbers, which were measured 2026-10-15.

Proven with the Tauri shell:

- the shell starts the same bundled JVM backend and the real built UI at the loopback URL, and
  upload → completed → transcript works through the real window, including the first run's model
  downloads from the pinned manifest (item 1);
- the `.deb` installs and runs in a stock `ubuntu:24.04` container with only its declared
  dependencies (item 2);
- the flake package and the self-contained bundle run on a machine with no Nix and no host
  WebKitGTK (item 3), and the Debian bundler declares `libwebkit2gtk-4.1-0` only because the
  configuration now lists it — its default `Depends` are empty;
- `tauri-driver` with `WebKitWebDriver` under `xvfb` drives the installed `.deb` through the
  journey in a stock `ubuntu:24.04` container (item 5's Tauri half).

| Measurement | Electron (item 50, 2026-10-15) | Tauri 2 (item 64, 2026-10-19) | Difference |
| --- | --- | --- | --- |
| `.deb` payload, unpacked · **cross-date** | 628 MB | 479.2 MB | −148.8 MB (−23.7%) |
| `.deb` payload as `tar \| xz -6` · **cross-date** | 233 MB | 159.4 MB | −73.6 MB (−31.6%) |
| Flake package closure · **cross-date** | ~1598.5 MiB | 1542.8 MiB | −55.7 MiB (−3.5%) |
| Bundle executable | not recorded | 490.9 MiB | — |
| glibc floor (`readelf -V`) | 2.38 | 2.39 | +0.01 |
| Oldest distribution, `.deb` | Ubuntu 23.10+ / Debian 13+ | Ubuntu 24.04+ / Debian 13+ | drops 23.10 |
| Browser engine | bundled Chromium 44.7.1, one pin | the distribution's WebKitGTK 4.1 | not bundled |
| Network path from the window | denied, ADR 0055, e2e-tested | **not enforceable by the public API** | regression |

**The three size rows are stale cross-date comparisons, not like-for-like ones.** Electron's
2026-10-15 build shipped the tools of its day (149 MB of them); Tauri's carries item 62's audio-only
ffmpeg, so the same staged tools now weigh 268.9 MB. The comparable unpacked baseline is therefore a
*current* Electron `.deb` at ~748 MB (inferred: 628 − 149 + 269, not measured), against which
Tauri's 479.2 MB saves **268.8 MB (35.9%)**. No current Electron archive was built, so **the current
compressed saving is unknown**; the 31.6% row compares a 2026-10-19 `xz` of Tauri's payload with a
2026-10-15 `xz` of Electron's, and the 3.5% closure row is affected the same way.

Tauri's payload split (13.8 MB binary, 101.5 MB backend, 94.4 MB jlink JRE, 268.9 MB tools, 0.6 MB
UI) is Electron's except for the shell itself: Electron was 281 MB of the 628 MB payload, and the
backend, the JRE, the tools and the UI stay in both artifacts — 465.4 MB of Tauri's 479.2 MB is
everything that is not the shell. The saving is the shell, nothing more; the closure difference is
the same story, because both closures carry the same tools, backend, JRE and UI.

## Decision

**Keep Electron as the Linux desktop shell.** Item 64 closes; no migration item is filed.

The savings are real and smaller than the question implies, while the costs land on an invariant:

- **ADR 0055 is not expressible under Tauri 2.** Measured with the shell's own capabilities in
  place, a `fetch` from the page to the machine's own non-loopback address returned **200**, and the
  stand-in server logged the request; `navigator.permissions.query` reported `prompt` rather than
  `denied`. Tauri's `on_web_resource_request` covers only `tauri://` assets — unlike Electron's
  `webRequest.onBeforeRequest`, there is no subresource filter in the public API — so the only
  remedies are a CSP served by the backend (shared code, a change to `main`) or accepting a weaker
  guarantee than the ADR states. What did hold: `window.open` denied and navigation away refused,
  which is exactly the part Electron and Tauri get from their window policies.
- **The saving is one shell out of the payload.** Against the 2026-10-15 baseline it is 24% unpacked
  and 32% compressed, and against the inferred current baseline ~36% unpacked (compressed
  unknown); either way, backend, JRE, tools and UI are 465.4 MB of Tauri's 479.2 MB and 347 MB of
  Electron's 628 MB — about 467 MB of both with today's tools — and a shell swap does not shrink
  them. The 3.5% closure difference is within the noise of a nixpkgs update.
- **The browser floor moves to the distribution.** The `.deb` depends on `libwebkit2gtk-4.1-0`
  with no version pin, so a distribution's WebKit decides the UI's behavior and bugs. That cost
  showed up during the spike: WebKitGTK 2.54.1 on GTK3 scales the page by a negative
  `gdk_screen_get_resolution()/72` when no XSETTINGS manager is present, collapsing the UI to a few
  pixels and breaking WebDriver hit testing (the shell sets 96 DPI after GTK init); the transcript
  page's `<audio>` needs GStreamer `coreelements`/`base`/`good` or the WebProcess dies; an unwrapped
  GTK binary needs GTK's compiled schema directories for the file chooser; the file chooser follows
  the system's dark preference only after reading the portal's `color-scheme` before GTK starts and
  setting `GTK_THEME`; and WebKitGTK's `<select>` popups stay light whatever `GTK_THEME` says,
  because they read the page's CSS `color-scheme` — a UI gap, not a shell one.
- **The `.deb` reaches fewer machines.** GLIBC_2.39 (the Rust binary) versus Electron's 2.38, so
  Ubuntu 24.04+ instead of 23.10+. The flake package and the bundle carry their own glibc and are
  unaffected.
- **The e2e path is more fragile.** `tauri-driver` binds 127.0.0.1 in its own namespace, so the
  container needs host networking (or the client inside it); apt's WebKitWebDriver 2.52.6 has no
  `/status`, so "the driver is up" has to be any HTTP answer; apt's `xvfb-run` stalls without
  `xdpyinfo`; and the container needs the store's `xvfb-run`, `tauri-driver`, `webkitgtk-driver`,
  `xvfb` and model fixtures mounted in. Electron's packaged e2e already runs in CI through
  Playwright and `electron-builder` with none of that.
- **Two shell-side behaviors were not proven.** Under `tauri-driver`, the JVM on the run's port was
  still alive 30 s after WebDriver closed the window (item 50's quit semantics are enforced by the
  Electron main process); the spike records the observation but does not fail on it. And the Debian
  bundler needs the shell's own workarounds to be packageable at all: the Nix devshell leaves the
  store's ELF interpreter and RPATHs in the binary (`patchelf` fixes), and `tauri-build` re-copies
  `bundle.resources` over the read-only staged JRE unless the copy is cleared or `TAURI_CONFIG`
  disables it.

Not measured, and not part of the decision: startup time and resident memory for either shell. No
Electron baseline was ever recorded, and the approved test list named sizes, floors and the e2e
path only; a Tauri number alone would have had nothing to be compared against.

## Consequences

- ADR 0053's three artifacts, its single pinned Electron 44.7.1 and ADR 0055's network policy stay
  as they are; CI's desktop jobs and the existing Playwright journey are untouched, since nothing
  merges.
- The frontend question stays answered by the same arithmetic: the UI is 0.6 MB of a 479–628 MB
  payload, so React is not the cost.
- The prototype stays on `roadmap/64-tauri-evaluation` for inspection. A future re-evaluation
  starts from it rather than from zero — under a changed ADR 0055 (a backend-served CSP), or if
  Tauri grows a subresource request filter.
- **Two Tauri-only observations, recorded here and in item 64's test list, with no follow-up**
  because Electron stays: under `tauri-driver` the backend was still running 30 s after the window
  closed (item 50's quit semantics are unchanged), and WebKitGTK 4.1 renders `<select>` popups from
  the page's CSS `color-scheme`, so they stay light under a dark GTK theme (Electron's popups follow
  the system). Neither is filed as an item.
- The Windows and macOS desktop items (48, 49) are unaffected: they build on item 50's Electron
  app, which this decision leaves in place.
