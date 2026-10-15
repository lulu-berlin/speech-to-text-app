# 53 — The Nix store cache: one writer per key, versioned past the stale entry

Status: spec-approved
Depends on: [51](../docs/specs/ci-verification.md) (done)
Blocks: —

## Goal

The `nix-community/cache-nix-action` store cache must actually accelerate a warm run: exactly one
job writes each immutable key, the key version moved past the stale evaluation-only entry, and the
`nix-build` job has the sources its key step and `nix build` need — so iterating on CI stops
re-fetching the ~1.3 GiB closure on every push.

## Evidence

- `.github/workflows/ci.yml` ran all three Nix jobs (`nix-devenv`, `nix-flake-check`, `nix-build`)
  in parallel, each with the composite `.github/actions/nix-toolchain`, which saved the *same* key
  `nix-<os>-<sha>` by default. GitHub Actions cache keys are immutable, so the first successful
  post-job save wins: a small, evaluation-only store locks the key in and a later `nix build` can
  never replace it. The `main` entry was 122 MB against 2.2 GB on the PR ref
  (`docs/specs/ci-verification.md`, the run-38631404614 entry; the cache inventory also shows caches
  are ref-scoped).
- `nix-community/cache-nix-action@v7` (action.yml read at SHA `7df957e3`) saves in a post step gated
  on `post-if: success()`, so a red `nix-build` writes nothing at all. Run #38650675971
  (`main`, `656fcd04`) had exactly that: `Nix (build)` red at
  `ProcessingEngineAtomicTransitionsSpec.kt:302` — roadmap [47](../docs/specs/atomic-transitions-spec-flaky.md)
  — while the same commit's CI fast gate passed; its post-job save was skipped.
- The toolchain's own key step needs the checkout: it hashes `flake.lock`, `devenv.lock`,
  `devenv.yaml`, `devenv.nix`, `nix/**` and the `versions.json` pins under `set -euo pipefail`, and
  `nix build` builds the flake in the workspace. A pending edit had dropped
  `actions/checkout@v7` from `nix-build`, which fails the key step (`jq`/`cat` on missing files) and
  leaves the build nothing to build.

## Proposal

1. `nix-toolchain` gains a `save-cache` input (default `false`); only `nix-build` passes
   `save-cache: 'true'`, so the job that materializes the expensive closure (including whisper-cpp's
   Vulkan build) is the only writer, and the other two Nix jobs restore it read-only.
2. The key is versioned `nix-<os>-v2-<sha>`; `restore-prefixes-first-match: nix-<os>-` still
   restores the previous namespace once, so the first run after this lands is warm rather than cold.
   `gc-max-store-size-linux: 4G` stays: a key change transiently leaves the previous entry alive
   beside the Gradle, pnpm and downloads caches.
3. All three Nix jobs keep `actions/checkout@v7` before the toolchain, with the reason recorded in
   the workflow.

Not an app feature, so no e2e test: the verification is the workflow's own runs, and the user
observes and reports them (ADR 0046's exemption for build/packaging items).

## Open questions

- Should the read-only jobs use `restore-prefixes-all-matches` (the action's cross-ref mode) instead
  of relying on `actions/cache`'s own base-branch fallback, so a fresh branch can warm from `main`'s
  entry? Measure on the first warm run before changing it.
- Should the pin selection fail loudly rather than silently drop a renamed key? `jq -Sec '{…}'`
  returns the subset present, so a renamed pin silently changes the key (measured as a cold run, not
  a red one).
- Is 4G still the right store cap once the v2 entry is measured?

## Test list (proposed; behaviors only)

- [ ] A run whose `nix-build` job succeeds writes exactly one `nix-<os>-v2-…` store entry, and the
      other two Nix jobs' cache steps run read-only (`save: false`)
- [ ] On a following run the entry is restored (`Cache hit for: nix-<os>-v2-…`) and `nix build`
      finishes without re-fetching the closure (compare "these N paths will be fetched")
- [ ] The `nix-build` job's key step succeeds and `nix build` completes, i.e. the sources it needs
      are checked out
- [ ] (must not break) the fast gate, `nix flake check`, `devenv test`, `gradle integrationTest` and
      the e2e job stay green, and the Gradle, pnpm and downloads caches keep hitting

## Acceptance criteria

- A warm `main` run restores the v2 store and `nix build` takes seconds, with no check weakened and
  no key written by more than one job.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-14: filed at the user's request to re-home the cache change that had accumulated under
  item 51 (merged) as its own tracked item, marked as item 53 because `roadmap/52-*` is
  `52-edit-transcription` (spec-approved). The change itself was written and reviewed under item 51's
  umbrella; this file records its evidence, proposal and test list. Branch
  `roadmap/53-nix-store-cache-v2`, one commit on `main` (ADR 0050) for the PR; the user pushes it and
  reports the runs. Ticking an item requires a real run.
