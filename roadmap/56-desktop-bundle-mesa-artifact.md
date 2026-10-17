# 56 — Prebuild the desktop bundle's Vulkan-only Mesa for Linux x86_64

Status: spec-approved
Depends on: [50](../docs/specs/electron-desktop-app.md) (the bundle output this artifact accelerates)
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

- [ ] On Linux x86_64 with a clean Nix store, `nix build .#desktop-bundle` consumes the sha256-pinned
      Mesa release asset instead of compiling Mesa from source; the bundle contains the expected
      Vulkan manifest and libraries, and its existing GPU transcription journeys remain correct.
- [ ] A system/architecture with no registered artifact entry continues to use the source derivation;
      this item registers only `x86_64-linux`.
- [ ] A registered but missing or hash-mismatched artifact fails naming the artifact and its pin,
      without silently compiling that configured output from source.
- [ ] (must not break) `nix build .`, `nix flake check`, the `.deb` packaged specs and the bundle's
      packaged specs stay green.

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
