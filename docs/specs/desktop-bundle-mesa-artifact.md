# 56 — Prebuild the desktop bundle's Vulkan-only Mesa for Linux x86_64

Status: done (roadmap item 56, approved 2026-10-18)
Depends on: [50](electron-desktop-app.md) (the bundle output this artifact accelerates)
Blocks: —

## Goal

The Linux x86_64 desktop bundle consumes a versioned, sha256-pinned prebuilt artifact for its
Vulkan-only Mesa output instead of compiling that output from source on each uncached machine and CI
run. Structure artifact selection by system/architecture so future desktop targets can add entries;
this item publishes and supports only `x86_64-linux`.

## Evidence

- The known-issues entry in item 50's worktree records that the bundle's Mesa override is not in
  `cache.nixos.org` and therefore builds from source. The selected Vulkan-only output is 158.8 MiB;
  the stock Mesa closure would add about 1.07 GiB, including 550 MiB of LLVM build input.
- PR #9's run `38825990732` spent about eight minutes building the custom `whisper-cpp` output for
  `.#desktop`; that build completed. The desktop-nix e2e then failed after launching the Ubuntu
  container with exit 6. Item 56 targets the separately measured Mesa build cost, not that container
  failure (tracked by item 57).

## Decisions (consultation, 2026-10-16)

- **Artifact form**: build the Mesa output once, publish it as a GitHub Release asset, and consume it
  as a pinned, sha256-verified Nix fetch. The human uploads release assets; agents do not publish to
  GitHub.
- **Platform scope**: only `x86_64-linux` is built and populated. Key artifact selection by system and
  architecture so later items can extend the map to other Linux architectures or macOS without
  changing its shape. No aarch64 or Darwin artifact is built here; a system with no artifact entry
  continues to use its source derivation.
- **Failure behavior**: a configured asset that is missing or fails its sha256 pin fails the build
  naming the artifact; it does not silently fall back to compiling that configured artifact.

## Proposal

Provide the bundle's Vulkan-only Mesa output from the pinned Linux x86_64 release asset. Keep the
existing Nix source derivation available as the fallback when a system/architecture has no registered
artifact. Verify the unpacked output still supplies the Vulkan ICD manifest and driver libraries the
bundle needs, and does not reintroduce OpenGL drivers or LLVM into the runtime closure.

## Test list (approved 2026-10-16; behavioral scenarios)

- [x] On Linux x86_64 with a clean Nix store, `nix build .#desktop-bundle` consumes the sha256-pinned
      Mesa release asset instead of compiling Mesa from source; the bundle contains the expected
      Vulkan manifest and libraries, and its existing GPU transcription journeys remain correct.
      (cycle 4, 2026-10-18)
- [x] A system/architecture with no registered artifact entry continues to use the source derivation;
      this item registers only `x86_64-linux`. (cycle 1, 2026-10-17; the "only `x86_64-linux`"
      clause is asserted in item 1's cycle)
- [x] A registered but missing or hash-mismatched artifact fails naming the artifact and its pin,
      without silently compiling that configured output from source. (cycle 2, 2026-10-17)
- [x] (discovered, cycle 2; confirmed 2026-10-17) A registered artifact whose pinned `storePath` is
      not the source derivation's out path (nixpkgs bumped or the Mesa override edited) fails at
      evaluation, naming the artifact, its pin and both paths, without fetching or compiling Mesa.
      (cycle 3, 2026-10-17)
- [x] (discovered, cycle 4; confirmed 2026-10-18) Every pinned reference is in the prebuilt Mesa's
      closure, so the bundle's payload carries the libraries its drivers load. (enforced by
      `desktop-mesa-prebuilt` since `412c8d0f`)
- [x] (must not break) `nix build .`, `nix flake check`, the `.deb` packaged specs and the bundle's
      packaged specs stay green. (item 4, 2026-10-18)

## Acceptance criteria

- A cold Linux x86_64 build of `.#desktop-bundle` uses the pinned Mesa artifact, not a source-built
  Mesa output; a build with an unregistered target remains source-build capable.
- Artifact integrity failures are explicit; Mesa's runtime Vulkan files and GPU transcription behavior
  are unchanged.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-16: the user chose prebuilt pinned artifacts rather than a binary cache service; Linux
  x86_64 is the implementation target, with a system/architecture-keyed structure for future targets.
  Test list approved as worded above. This is a build-cost optimization; it does not fix item 50's
  desktop-nix container exit 6 or the separate item-55 bundle-job lock-probe failure. Status:
  spec-approved.
- 2026-10-17: workspace `workspaces/56-desktop-bundle-mesa-artifact` (branch
  `roadmap/56-desktop-bundle-mesa-artifact`) created from `main` `3f4ad710`, after item 50 landed
  (#9). Status: in-progress.
- 2026-10-17 (mechanism, before cycle 1): measured in the sandbox, which cannot build (no user
  namespaces for a Nix store) but can evaluate: the source Mesa is
  `/nix/store/mgdzk8nsq79dld1gzbihy0jxkd7jmnak-stt-bundle-mesa-vulkan-26.2.4.drv` →
  `/nix/store/r68qzz1ndp2d7bbhchjb28ixkbakrq3y-stt-bundle-mesa-vulkan-26.2.4` (nixpkgs `494ce7fd`);
  building it cold fetches 249 paths (1.0 GiB download, 3.9 GiB unpacked, LLVM and clang among them)
  before compiling. Stock `mesa-26.2.4` in cache.nixos.org (a proxy for the override's reference
  shape): NAR 274 MiB, closure 1.05 GiB, 22 direct references including itself, glibc, gcc-lib,
  libdrm, libxcb, wayland, zstd, expat, elfutils — so an unpacked copy at another store path must
  rewrite its self-references. `flake.lock` has one commit (2026-10-05). Options compared: **A**, a
  fixed-output `.tar.zst` release asset re-linked into the same nixpkgs (same-name unpack derivation,
  same-length rewrite of the output's own hash, the pinned direct references as its only inputs, a
  scan that every remaining store path is a pinned reference or the output itself, ICD and no-LLVM
  checks, and an evaluation-time gate that fails when the pinned `storePath` differs from the source
  derivation's out path, e.g. after a nixpkgs bump); **C1**, the release laid out as a signed binary
  cache used through `extra-substituters` (exact store path, but it helps only machines that trust the
  substituter, a missing asset silently compiles, and it needs a signing key); **C2**,
  `builtins.fetchClosure` (needs the `fetch-closure` experimental feature on every evaluating machine,
  otherwise the flake fails to evaluate). **The user chose A**: it works on every x86_64-linux Nix
  machine that builds the bundle (`.#desktop-bundle`, `nix bundle --bundler .#desktop`, CI) with no
  configuration, and every failure is loud. Clarified on the way: `nix run .#desktop` never builds
  this Mesa (it uses the host's drivers through `/run/opengl-driver`), so A does not change it.
  Pending then: the user's measurements of the source output and confirmation of the map's shape
  and the release naming.
- 2026-10-17 (the user's measurements, at `792df0e1`, in the worktree): `nix build --no-link
  --print-out-paths .#desktop-bundle.vulkanMesa` →
  `/nix/store/r68qzz1ndp2d7bbhchjb28ixkbakrq3y-stt-bundle-mesa-vulkan-26.2.4` (already in the store;
  the path the sandbox evaluated). `nix-store -q --references`: 13 libraries plus itself —
  `h4wfwic1…-glibc-2.44-25`, `0yvm79df…-libdrm-2.4.134`, `cvr8yyrr…-libxcb-1.17.0`,
  `gvhdvn54…-systemd-minimal-libs-261.3`, `j7qx4s4m…-gcc-16.2.0-lib`, `ja1if6cm…-libxcb-keysyms-0.4.1`,
  `li1008zy…-wayland-1.26.0`, `mc33sic0…-libdisplay-info-0.4.0`, `ph4ib8mx…-zstd-1.5.7`,
  `s7306mp3…-elfutils-0.195`, `vv038kwd…-libxshmfence-1.3.3`, `znrm90al…-libx11-1.8.13`,
  `zrkqwk09…-expat-2.8.5` (no LLVM, no `cross_tools`). `nix path-info -sSh`: **68.7 MiB** NAR,
  **158.8 MiB** closure. Files naming the output's own path: the three ICD manifests
  (`share/vulkan/icd.d/{nouveau,intel,radeon}_icd.x86_64.json`) and the three drivers
  (`lib/libvulkan_{nouveau,intel,radeon}.so`). Symlinks: none. The output as a deterministic tar,
  `zstd -19`: **12,182,422 bytes (11.6 MiB)** — the asset's expected size.
- 2026-10-17: the user confirmed the map and the naming — `nix/desktop-artifacts.nix`, keyed
  `mesaVulkan.<nix system>` with `url`, `sha256`, `storePath` and `references`, outside
  `versions.json`; release tag `desktop-mesa-v1` (the next tag on every re-publish, old releases
  kept); asset `stt-bundle-mesa-vulkan-26.2.4-x86_64-linux.tar.zst`; a flake output
  `.#desktop-bundle-mesa-asset` that builds the asset and its pin — and test-list item 2 as cycle 1.
  Its clause "this item registers only `x86_64-linux`" is asserted in item 1's cycle, when the entry
  exists.
- 2026-10-17 (cycle 1, item 2): `checks.x86_64-linux.desktop-mesa-unregistered-system` evaluates the
  bundle Mesa for `pkgsCross.aarch64-multiplatform` and asserts it is the source derivation (same out
  path). Red (sandbox, `nix eval --raw .#checks.x86_64-linux.desktop-mesa-unregistered-system.drvPath`,
  exit 1): `error: Path 'nix/desktop-mesa.nix' does not exist in Git repository`. Green: the Mesa
  override moved unchanged from `nix/desktop-bundle.nix` to `nix/desktop-mesa.nix` (`source`, and
  `mesa` — for now always `source`, since no system has an artifact yet); the bundle takes `mesa`.
  Same command: exit 0, `/nix/store/i7rz2f5f51hhzlyz5ayx4453xajal0r3-desktop-mesa-unregistered-system.drv`.
  `.#desktop-bundle.vulkanMesa` still evaluates to
  `/nix/store/r68qzz1ndp2d7bbhchjb28ixkbakrq3y-stt-bundle-mesa-vulkan-26.2.4`, so the bundle is
  unchanged. The sandbox cannot evaluate the bundle itself (its `bwrapLibs` is import-from-derivation)
  nor build the check: `nix flake check` on the user's machine is unverified.
- 2026-10-17 (the user's runs after cycle 1, at `f6e4cb63`): `nix eval --raw .#desktop-bundle.outPath`
  in the worktree and in the main tree both print
  `/nix/store/r1x4hgvcjk4mmxqg2c6q8kjy3ilna7lq-stt-app-bundle` — the bundle is unchanged by the move;
  `nix build --no-link .#checks.x86_64-linux.desktop-mesa-unregistered-system` succeeded. Refactor
  checkpoint: none suggested, none requested. Item 2 ticked.
- 2026-10-17 (cycle 2, item 3; the user chose T1: our own fetch step, tested in `nix flake check`):
  `checks.x86_64-linux.desktop-mesa-artifact-integrity` registers a fixture entry pinned to 64
  zeros, once at the `file://` URL of a store fixture and once at a URL with no file behind it, runs
  the asset's own fetch step for both and expects each to fail naming the asset, its url and its
  sha256. Red (sandbox, `nix eval --raw .#checks.x86_64-linux.desktop-mesa-artifact-integrity.drvPath`,
  exit 1): `error: function 'anonymous lambda' called with unexpected argument 'artifacts'`.
  Implementation: `nix/desktop-artifacts.nix` (`mesaVulkan = {}`); `nix/desktop-mesa.nix` takes
  `artifacts` and, for a registered system, fetches the asset with a fixed-output step of its own
  (curl, then a sha256 comparison that fails naming the asset, url and sha256) and unpacks it under
  the source's name. The fetch logic run locally in the sandbox: wrong pin and missing file exit 1
  with the message, a matching pin exits 0. First build by the user (`nix build --no-link -L
  .#checks.x86_64-linux.desktop-mesa-artifact-integrity`) failed in the check itself: its "no
  source build" assertion took `closureInfo` of the registered Mesa's `.drv`, and exporting a
  `.drv`'s closure makes Nix build every output in it — so it built the wrong-pin asset (which
  failed, with our message: `desktop artifact ynx15kjx…-stt-bundle-mesa-vulkan-fixture.tar.zst
  (mesaVulkan.x86_64-linux in nix/desktop-artifacts.nix): does not match its pin: got sha256
  3aa08327…`, then `url:` and `sha256: 0000…`), and its `source.drvPath` would have built the source
  Mesa. The user approved the replacement: at evaluation, the source Mesa's `.drv` is a direct input
  (`builtins.getContext` of the attributes) of none of the registered Mesa's own derivations — the
  unpack, the asset, its fetch script. The check's inputs are now only the two fetch scripts, the
  fixture and stdenv (sandbox evaluation). Green (the user's runs, uncommitted tree on `7630c045`):
  `nix build --no-link -L .#checks.x86_64-linux.desktop-mesa-artifact-integrity` and
  `nix build --no-link .#checks.x86_64-linux.desktop-mesa-unregistered-system` succeeded;
  `nix eval --raw .#desktop-bundle.outPath` still prints
  `/nix/store/r1x4hgvcjk4mmxqg2c6q8kjy3ilna7lq-stt-app-bundle` (the map is empty, so the bundle is
  unchanged).
- 2026-10-17 (checkpoint after cycle 2): the user approved one refactor — `expectFailure` binds the
  asset once (`Refactor: bind the asset once in the integrity check`); the check's `.drv` is
  unchanged (`/nix/store/g53ny5b6kvybvy1vq5hr7yk331srmjam-desktop-mesa-artifact-integrity.drv`,
  sandbox evaluation), so the user's green build stands. The user confirmed the discovered stale-pin
  scenario. Item 3 ticked.
- 2026-10-17 (cycle 3, the discovered stale-pin scenario; the user approved exposing the thrown
  text): `checks.x86_64-linux.desktop-mesa-stale-pin` registers a fixture entry whose `storePath`
  is `/nix/store/00000000000000000000000000000000-stt-bundle-mesa-vulkan-26.2.4`, asserts that
  `builtins.tryEval` of the registered Mesa's out path fails and that the exposed error text names
  the asset, its url, sha256 and storePath, and the source's out path. Red (sandbox, `nix eval --raw
  .#checks.x86_64-linux.desktop-mesa-stale-pin.drvPath`, exit 1): `error: desktop-mesa-stale-pin: a
  pin made from /nix/store/00000000000000000000000000000000-stt-bundle-mesa-vulkan-26.2.4 evaluated
  to /nix/store/55mvi0lijgrxcnxq9pfyickv2zjd5ndw-stt-bundle-mesa-vulkan-26.2.4`. Green:
  `nix/desktop-mesa.nix` compares the pinned `storePath` with the source's out path and, when they
  differ, throws (and exposes as `pinError`) the text below; the test's expected strings drop their
  store-path context (`lib.hasInfix` cannot take it). Same command: exit 0,
  `/nix/store/ri7xmx3khih3dbzadcnkp959chb2z7ci-desktop-mesa-stale-pin.drv`; the two earlier checks'
  `.drv`s and `.#desktop-bundle.vulkanMesa` (`r68qzz1n…`) unchanged. The message for a
  release-shaped stale pin (sandbox evaluation):
  `desktop artifact stt-bundle-mesa-vulkan-26.2.4-x86_64-linux.tar.zst (mesaVulkan.x86_64-linux in
  nix/desktop-artifacts.nix) was made from another Mesa build than this flake's; rebuild the asset
  from this flake's Mesa and pin the new release.` followed by `url:`, `sha256:`, `storePath:` and
  `this flake's Mesa: /nix/store/r68qzz1n…-stt-bundle-mesa-vulkan-26.2.4`.
- 2026-10-17 (checkpoint after cycle 3): suggested (1) compute the asset name once in
  `nix/desktop-mesa.nix` (it was written out in both the fetch and the stale-pin message) and (2)
  share the integrity and stale-pin checks' fixture and pin (advised against: the pins differ on
  purpose). The user approved (1): `pin` and `assetName` now sit before `prebuilt`, which takes no
  argument (`Refactor: compute the Mesa asset name once`). All three checks evaluate to the same
  `.drv`s as before (`ri7xmx3k…`, `g53ny5b6…`, `i7rz2f5f…`) and `.#desktop-bundle.vulkanMesa` is still
  `r68qzz1n…` (sandbox evaluation). The stale-pin check's assertions all run at evaluation; the
  user's build of it is still to be reported. Stale-pin item ticked.
- 2026-10-18 (cycle 4, item 1): `checks.x86_64-linux.desktop-mesa-prebuilt` — while evaluating, it
  asserts that the map registers exactly `x86_64-linux`, that the bundle's Mesa is not the source
  derivation, and that the source `.drv` is a direct input of none of the prebuilt Mesa's own
  derivations; built, it asserts the three ICD manifests and drivers, that no file names the asset's
  build path, that every remaining store path is itself or a pinned reference, and (via
  `disallowedRequisites`) that no LLVM is in the closure. Red (sandbox,
  `nix eval --raw .#checks.x86_64-linux.desktop-mesa-prebuilt.drvPath`, exit 1): `desktop-mesa-prebuilt:
  this item registers only x86_64-linux, but nix/desktop-artifacts.nix registers` (nothing).
  Implementation: `nix/desktop-artifacts.nix` gains the real pin (asset
  `stt-bundle-mesa-vulkan-26.2.4-x86_64-linux.tar.zst`, sha256 `37f340e0…`, `storePath` `r68qzz1n…`,
  the 13 references the user's `nix-store -q --references` printed in cycle 2's measurements); the
  prebuilt Mesa unpacks and re-links it (the asset's own path in the three manifests and three
  drivers becomes the output's, same length; the pinned references are inputs; a scan fails the
  build on a surviving old path or a stray store path; `disallowedRequisites` keeps LLVM out);
  `packages.x86_64-linux.desktop-bundle-mesa-asset` builds the asset and prints its pin (the user
  built it: 12,182,181 bytes, sha256 `37f340e0…`) and the user uploaded it as the prerelease
  `desktop-mesa-v1`. The 13 pinned references were also cross-checked against nixpkgs' own outputs
  (`systemd-minimal-libs` is `pkgs.systemdLibs`, `zstd`/`elfutils` their `out`). Four build failures
  while going green, all in the new check/build code and fixed: `jq` missing from the check's
  inputs; `$driver_icd` read as one variable (`${driver}_icd`); `\$manifest` left a literal `$` in
  the builder; and `perl`'s `$ENV{old}` was unset because `old` was only a shell variable, which
  rewrote the manifests into 11.5 kB of garbage — now exported for the command (`old="$old"
  out="$out" perl …`), reproduced and fixed in the sandbox (154-byte valid JSON). Green (the
  user's runs): the check built; `nix eval --raw .#desktop-bundle.vulkanMesa.outPath` →
  `/nix/store/054f0b73…-stt-bundle-mesa-vulkan-26.2.4`; `nix build -L .#desktop-bundle` succeeded
  (17m14s, ffmpeg 8.1.2 built from source too); `nix-store -q --requisites $(nix path-info
  --derivation .#desktop-bundle) | grep -c mgdzk8ns…` → `0`, so the source Mesa is not in the
  bundle's build graph on any store; the integrity check still green. Item 1's GPU journeys
  (`test:desktop:bundle`, `:bundle:amd`, `:bundle:nvidia`) and item 4's packaged specs are pending.
- 2026-10-18 (discovered: the prebuilt Mesa's closure was incomplete; the AMD GPU journey failed):
  the user's `test:desktop:bundle` passed (11 passed, 3 skipped), `:bundle:nvidia` passed (12
  passed, 2 skipped), and `:bundle:amd` failed with `no /RADV/ device among the probe's GPUs: {}`.
  Diagnosis (the user's runs): the bundle's Mesa path was right and its payload carried it, but the
  Vulkan loader inside an ubuntu:24.04 container reported `libelf.so.1: cannot open shared object
  file` and `libxcb-keysyms.so.1: …` when dlopening the drivers, so no device existed. Cause:
  `nix-store -q --references` of the prebuilt output listed only glibc, gcc-lib, zstd and itself,
  and its closure had none of the other ten pins — Nix records as references only the store paths
  that are inputs of the derivation, and a pin's paths are plain strings in
  `nix/desktop-artifacts.nix`. Fix: `nix/desktop-mesa.nix` attaches `path` context to each pin
  (`builtins.appendContext p {"${p}" = {path = true;};}` — works in pure evaluation, unlike
  `builtins.storePath`), so all thirteen are inputs; the check now also fails unless every pinned
  reference is in `closureInfo` of the prebuilt Mesa. Committed `412c8d0f`. Green (the user's runs
  on it): the check built; the bundle's Mesa is `/nix/store/f64v8qkx…`, `nix-store -q
  --references` → 14 (13 + itself); `nix build -L .#desktop-bundle` (2m17s, payload 473 MiB); and
  `test:desktop:bundle:amd` **12 passed, 2 skipped** — spec 10 found the Radeon 680M through the
  bundled RADV and transcribed on it. Re-running `:bundle` and `:bundle:nvidia` on this bundle is
  pending.
- 2026-10-18 (item 1 complete): on the final bundle `/nix/store/29np3s3p…-stt-app-bundle` the user's
  `test:desktop:bundle` (Alpine, CPU) gave **11 passed, 3 skipped**, `:bundle:nvidia` **12 passed,
  2 skipped** (spec 11 transcribed on the RTX 3050 through the host's borrowed driver) and
  `:bundle:amd` **12 passed, 2 skipped** (spec 10 on the Radeon 680M through the bundled RADV).
  Item 1 ticked.
- 2026-10-18 (item 4, the must-not-break sweep, the user's runs at `d367db09`): `nix build .`
  succeeded; `nix flake check --print-build-logs` passed (all four item-56 checks, `versions-sync`,
  `devenv-pin-sync`, `desktop-model-free`; the archive was warm, so it took 8s);
  `pnpm --dir e2e test:desktop` (the `.deb` in ubuntu:24.04) **11 passed, 3 skipped**; and
  `pnpm --dir e2e test:desktop:bundle` (Alpine) **11 passed, 3 skipped**. Item 4 ticked; the
  remaining unticked line is the discovered closure scenario, awaiting the user's confirmation.
- 2026-10-18 (final, the user's confirmations): the discovered closure scenario is confirmed and
  ticked. One refactor approved and done (`Refactor: bind the check's pin references once`; all four
  checks keep their `.drv`s). CI approved and changed: `desktop-bundle-e2e` is back in the `ci`
  aggregator's `needs` — roadmap 55 fixed the flaky pin and item 56 removed the Mesa build from the
  job — and the job's and the aggregator's comments say so (the aggregator's remaining exception is
  `model-hosts`); `actionlint` passes. **Follow-up for the root agent to file as a roadmap item:**
  the same pinned-release pattern for the bundle's ffmpeg. Evidence from this item: the bundle's
  build compiles `ffmpeg_8` (with SDL2 and `ffplay` off, `nix/desktop.nix:124-127`) from source —
  the user's 17m14s bundle build rebuilt it, and the override is not stock `ffmpeg_8`, so it is not
  in cache.nixos.org. Final results: the bundle Mesa is the pinned asset
  (`/nix/store/f64v8qkx…`), the source Mesa is absent from the bundle's build graph
  (`nix-store -q --requisites|grep` → 0), `nix build .` and `nix flake check` pass, the packaged
  specs pass in three containers (`.deb` 11+3, bundle 11+3, bundle AMD 12+2, bundle NVIDIA 12+2),
  and `nix build .#desktop-bundle` reused the asset (2m17s). Not yet verified: a literal empty-store
  build — the first CI run of `desktop-bundle-e2e` after the user pushes is that proof. Status:
  in-review.
- 2026-10-18 (main-tree review, approved by the user): no blocking findings. Verified in the
  sandbox: the published asset `desktop-mesa-v1/stt-bundle-mesa-vulkan-26.2.4-x86_64-linux.tar.zst`
  downloads at 12,182,181 bytes with sha256 `37f340e0…830bc`, exactly the pin, and its RADV manifest
  names the pinned build path `r68qzz1n…-stt-bundle-mesa-vulkan-26.2.4` that the re-link rewrites;
  `actionlint` and the documentation checks pass. Three LOW fixes, made by the root agent in the
  squash at the user's choice: the moved Mesa comment (`nix/desktop-mesa.nix:16-19`) no longer
  points at a known-issues row that is gone and says only a system without an asset builds from
  source; the asset's size above (cycle 4) read 12,182,422 bytes, the cycle-2 measurement of the
  source output's tar, not the published asset; and the `desktop-bundle-e2e` comment in
  `.github/workflows/ci.yml` now says that item 58's real-time completion waits can still fail its
  Nix build's `gradle check`, as they can the already-required `desktop-nix-e2e` and `nix-build`.
  The ffmpeg follow-up is filed as roadmap item 61 (draft). Spec and final test list moved here
  from `roadmap/56-desktop-bundle-mesa-artifact.md`; squashed to one commit (ADR 0050).
