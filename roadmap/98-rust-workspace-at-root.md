# 98 — The Rust workspace lives at the repository root, and its crates in `crates/`

Status: spec-approved
Depends on: [67](67-tui-frontend.md) (it splits `client/` into four crates on its own branch), [66](../docs/specs/local-service-and-frontends.md) (done; the workspace, the toolchain file and the version pin)
Blocks: —

## Goal

`Cargo.toml`, `Cargo.lock` and `rust-toolchain.toml` sit at the repository root, every crate lives
under `crates/`, and `client/` is gone. `cargo test`, `cargo fmt --check` and `cargo clippy` run from
the root, every place that names the old paths names the new ones, and CI, `checkVersions`, the Nix
package and the e2e harness stay green. The two ADRs this rests on — the layout and the direction
(the app moves to Rust, crate by crate) — are accepted and indexed, and item 92 (Electron 45) is
cancelled: its file, index row and graph node are gone.

## Evidence

No behaviour changes; this is a layout move with a short list of references, all of them mechanical.

- **The workspace files today:** `client/Cargo.toml` (workspace root, `resolver = "3"`, one member
  `stt-client`), `client/Cargo.lock`, `client/rust-toolchain.toml`, and `client/target/`
  (git-ignored at `.gitignore:26-27`).
- **Item 67 branches into four crates**, so the move is cheapest after it merges:
  `client/stt-app`, `client/stt-client`, `client/stt-tray` and `client/stt-tui` exist on its branch.
- **References that must change with the move:**
  - **CI** — `.github/workflows/ci.yml`: `working-directory: client` at `:122` (`client-check`), `:274`
    (`macos-client-check`) and `:668` (`windows-client-check`); the aggregator's `needs` at `:1539`
    names the three job ids.
  - **The Rust toolchain action** — `.github/actions/rust-toolchain/action.yml:26-27`: the cache path
    `client/target` and the key's `hashFiles('client/Cargo.lock')`.
  - **`checkVersions`** — the root `build.gradle.kts:117-127` reads
    `client/rust-toolchain.toml` and names it in its failure message and comment.
  - **Nix** — `flake.nix:187` (the comment tying `versions.json`'s Rust to the toolchain file);
    `devenv.nix:82,111` (the comment and the dev-shell note about the workspace in `client/`).
  - **e2e** — `e2e/lib/backend.ts:757-768`: `sttAppPath()` builds `client/target/debug/<exe>` with
    `--manifest-path client/Cargo.toml` and `cwd` `client`.
  - **Docs** — `AGENTS.md:27,95-97`, `README.md:228,235-236`, `docs/architecture.md:242,289`
    (`../client/stt-client` links), and `docs/specs/local-service-and-frontends.md` and
    `docs/specs/own-artifacts-and-platform-keys.md` wherever they name the directory.
  - **`docs/check-architecture-names.ts` / `check-links.ts`** read `docs/architecture.md`; the links
    must resolve after the move.
- **What does not change:** the crate names, the binaries' names, the release artifacts (ADR 0065),
  the service protocol, and the `versions.json` Rust pin.

## Decided (consultation, 2026-11-07)

- The workspace root moves to the repository root; the directory is `crates/`. Rationale: the app is
  moving to Rust, and a root workspace makes `cargo` a first-class command beside Gradle and pnpm
  rather than one hidden under `client/`.
- The move happens **after item 67 merges**, once: 67 already splits `client/` into four crates, so
  moving first would only mean rewriting its paths.
- **Both ADRs land as part of this item**: the layout decision (a root workspace, crates under
  `crates/`) and the direction (the app moves to Rust, crate by crate).
- **Roadmap item 92 (Electron 45) is cancelled**: the window is replaced by a Rust one (item 70), so
  updating Electron would be work on a stack that is leaving. Its file, row and graph node are
  removed with this item.
- **No full freeze of Kotlin and TypeScript.** New work in them is reduced to a minimum and avoided
  where possible: the direction is that new features and new work go to Rust, while the existing
  stacks keep being maintained (bug fixes, correctness, the gates staying green) until each area is
  replaced.
- **New features are planned after the move to Rust**, not added to the Kotlin or TypeScript code.
  An urgent feature may overrule that; the item that does so says why and records it.
- **Every roadmap item that plans to change Kotlin or TypeScript code targets the migrated Rust code
  if the migration lands before it starts.** The same retarget applies to part of an item: once an
  area exists in Rust, work on that area goes to the Rust code.
- **Approved items keep their scope while their stack exists**: 89 (the service package, JVM), 78
  (release gates, JVM and Electron) and 95/96 (Kotlin and frontend fixes) are not cancelled. They
  are retargeted by the rule above if their area moves first; nothing else is cancelled except 92.
- **`rust-toolchain.toml` moves to the root**, beside `Cargo.toml`, where it governs every `cargo`
  invocation; its comment says it applies to `cargo`, not to the JVM or Node consumers.
- **`Cargo.lock` moves to the root**, beside `flake.lock`; the release tooling (ADR 0065, item 89)
  reads it there.
- **The CI job ids stay `client-check`, `macos-client-check` and `windows-client-check`**, so the
  branch-protection required checks are untouched; only their `working-directory` and cache paths
  change.

## Proposal

1. `git mv client/Cargo.toml Cargo.toml`, `client/Cargo.lock Cargo.lock`,
   `client/rust-toolchain.toml rust-toolchain.toml`; `git mv client crates`; update the workspace
   `members`/`exclude` paths and `rust-version`.
2. `.gitignore`: `/client/target/` becomes `/target/` (and any per-crate `target/` stays ignored).
3. Update the references above: CI's three `working-directory` lines, the action's cache path and
   key, `checkVersions`' file path and message, the Nix comments, the e2e build path and `cwd`, and
   the docs' tree, commands and links.
4. **Job ids**: keep `client-check`, `macos-client-check` and `windows-client-check` for now, so the
   branch-protection required checks keep working; renaming them to `rust-check` is a separate
   decision (renaming a required check is a repository setting the user changes in GitHub, not a code
   change).
5. Write the two ADRs this item lands (they take the next two free numbers when the item starts;
   item 67 already adds one on its branch, so the numbers will have moved):
   - **Layout**: "The Rust workspace lives at the repository root; its crates live in `crates/`" —
     the decision, the references it touches, and the rule that new Rust crates land under
     `crates/`.
   - **Direction**: "The app moves to Rust, crate by crate" — new work goes to Rust; Kotlin and
     TypeScript keep being maintained (bug fixes, correctness, gates green) but new work in them is
     reduced to a minimum and avoided where possible; **new features are planned after the move to
     Rust**, an urgent feature may overrule that and says why; **an item that would change Kotlin or
     TypeScript code targets the Rust code once that area has moved**; each area moves when a Rust
     replacement exists; Gradle and pnpm go away when the last Kotlin and TypeScript code does. Its
     Consequences name the approved items the direction touches (89's JVM packaging, 78's release
     gates, 95/96's Kotlin and frontend fixes) and state that they keep their scope while their stack
     exists, retargeted by the rule above when their area moves.

## Open questions

None. (Settled 2026-11-09, see "Decided".)

## Test list

Draft, to settle with the user. A layout move: the proof is that every consumer still builds.

- [ ] (basic) From the repository root, `cargo test`, `cargo fmt --check` and
      `cargo clippy --all-targets -- -D warnings` pass for every crate under `crates/`.
- [ ] (basic) `./gradlew checkVersions` still holds `versions.json`'s Rust to the root
      `rust-toolchain.toml`, and its failure message names the new path.
- [ ] (basic) The e2e harness builds and launches `stt-app` from `crates/target/debug/…` (or the
      shared `target/`), proven by a journey that uses app mode.
- [ ] (basic) The three CI client jobs run in `crates/`, their cache key hashes the root
      `Cargo.lock`, and the PR's CI run is green on Linux, macOS and Windows.
- [ ] (basic) `nix build .` is green with the move (user-run: Nix does not build in the sandbox).
- [ ] (must not break) No file, script, action or doc names `client/` anymore, except historical
      records (roadmap items, ADRs, known issues) that quote it as history.
- [ ] (must not break) `docs/check-links.ts` and `docs/check-architecture-names.ts` pass over the
      updated `docs/architecture.md`.
- [ ] (basic) The two ADRs are accepted and indexed in `docs/decisions/README.md`; item 92's file is
      gone and no index row or graph node names it.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: a layout and build refactor. The existing journeys (with
the app-mode ones) running from the new paths are part of its proof.

## Approval log

- 2026-11-07: filed as a draft by the root agent at the user's request: a root `Cargo.toml` and a
  `crates/` directory, as the first step of moving the app to Rust.
- 2026-11-07: consultation (user): the move happens after item 67 merges; both ADRs (the layout and
  the direction) land as part of this item; roadmap item 92 (Electron 45) is cancelled, because the
  window becomes a Rust one (item 70) and updating Electron would be work on a leaving stack; and no
  full freeze of Kotlin and TypeScript — new work in them is reduced to a minimum and avoided where
  possible, while their existing code keeps being maintained. Item 92's file, index row and graph
  node are removed in this branch. Status stays `draft`; the spec and the test list are the next
  approval step.
- 2026-11-07: consultation, second round (user): new features are not added to the Kotlin or
  TypeScript code but planned after the move to Rust, unless an urgent feature overrules it; and any
  roadmap item that would change Kotlin or TypeScript code targets the migrated Rust code when the
  migration lands before it. Approved items keep their scope while their stack exists.
- 2026-11-09: consultation, third round (user): `rust-toolchain.toml` and `Cargo.lock` live at the
  root; the CI job ids keep their `client-*` names so the required checks are untouched. Status
  stays `draft` until the test list is approved.
- 2026-11-09: approved by the user: the decisions above and the 8-line test list as written. Status
  set to spec-approved.
