# AGENTS.md

## Project

A web app that transcribes uploaded audio files with speaker diarization. Backend: Kotlin + Ktor + SQLite; frontend: TypeScript + React + styled-components, served by the backend; one monorepo. Users upload many files into a work queue that is processed one task after another; the queue can be paused, each task can be paused or canceled, and tasks can be reordered. The queue and all transcription output are stored in the database, and past transcriptions stay viewable. All async work runs on Kotlin coroutines. Dependencies come from devenv; the final deliverable is a Nix flake derivation that runs backend, database, frontend, and the whisper model. `docs/oracle/transcribe.py` is the behavioral oracle for transcription, diarization, and output formats.

- Pending work: [roadmap/README.md](roadmap/README.md)
- Decisions: [docs/decisions/](docs/decisions/README.md)

## Non-negotiable rules

- **No code change is valid without the user's explicit approval.** Specs and test lists are approved before coding; every refactor is approved individually; diffs are approved before merging into `main`. Agents never merge, push, or rewrite `main` on their own.
- **TDD is mandatory** and follows Kent Beck's Canon TDD exactly as written in [§ Canon TDD](#canon-tdd).
- **Every spec and decision is documented.** Specs live in `roadmap/<NN-slug>.md` while pending and move to `docs/specs/<area>.md` when done; decisions are ADRs in [docs/decisions/](docs/decisions/README.md).
- **No Python in the app, build, or tests.** [docs/oracle/transcribe.py](docs/oracle/transcribe.py) is the reference oracle only: read it to port behavior; never run it from the app, build, or tests ([ADR 0002](docs/decisions/0002-no-python-oracle-only.md)).
- **All async/concurrent work uses Kotlin coroutines**: structured concurrency, an application-level `CoroutineScope` with `SupervisorJob`; blocking I/O (JDBC, subprocesses, file I/O) only inside `withContext(Dispatchers.IO)`; no raw `Thread`, `ExecutorService`, or `CompletableFuture` in app code.
- **Code is formatted**: Kotlin with ktlint, TypeScript/TSX with prettier; the standard check commands (`gradle check`, `pnpm test`) fail on format violations, so formatted code is required before any merge ([ADR 0015](docs/decisions/0015-code-formatting-ktlint-prettier.md)).
- **Dependencies come only from `devenv.nix` (dev) and `flake.nix` (packaging)**; never install tools ad hoc. An agent that needs a new development tool adds it to `devenv.nix` itself, commits it as its own change, and tells the user to re-enter the shell; Nix-level results are verified by the user ([ADR 0010](docs/decisions/0010-development-toolchain.md)).
- **Clean, readable code following [docs/conventions/backend.md](docs/conventions/backend.md) and [docs/conventions/frontend.md](docs/conventions/frontend.md)** (approved in roadmap item 02, see [docs/specs/repo-skeleton-and-conventions.md](docs/specs/repo-skeleton-and-conventions.md)). Deviations need a new ADR.

## Repository layout

```
AGENTS.md  devenv.nix  flake.nix  .editorconfig  .prettierrc
backend/   Gradle (Kotlin DSL) Ktor app; backend/deps.json = Nix lockfile for Gradle deps
frontend/  Vite + React + TS + styled-components (pnpm)
nix/       shared Nix expressions (models; more in item 16)
docs/decisions/  docs/specs/  docs/conventions/  docs/oracle/transcribe.py (reference oracle)
roadmap/   pending work, one file per item
workspaces/  git worktrees (ignored)
```

## Canon TDD

Source: https://newsletter.kentbeck.com/p/canon-tdd

1. **Test list, written with the user.** Before any test code for a roadmap item, the agent and the user write the list of behavioral test scenarios (basic case, edge cases, failure cases, "must not break existing behavior" cases) in the item's `## Test list` as `- [ ]` checkboxes. Behavioral analysis only: no implementation design in the list. The list is approved together with the spec.
2. **Exactly one test.** The agent proposes which list item to do next, with a one-line reason; the user confirms. The agent turns only that item into one concrete, runnable, automated test with setup, invocation, and real assertions (working backwards from the assertion is encouraged). Run it and record the failure output (red). Never convert several list items into tests ahead of time.
3. **Make it pass, for real.** Change the code so this test and all previous tests pass. Forbidden: deleting or weakening assertions; copying actual computed values into expected values (expected values come from independent derivation: hand calculation, the oracle's logic, or the spec); refactoring while going green. Scenarios discovered on the way are appended to the test list marked `(discovered)` and confirmed with the user at the next checkpoint. If a discovery invalidates the work done, stop and ask the user whether to push on or start over with a different test order.
4. **Refactor checkpoint — always ask.** After every green, stop and ask the user whether to refactor, giving concrete suggestions (duplication to remove, names to improve, extractions, simplifications, test readability), or state "no refactoring suggested" with the reason. Refactor only what the user approves; keep all tests green; never mix refactoring with behavior change; don't refactor beyond the current session's need; don't abstract early (duplication is a hint, not a command).
5. **Tick the item** in the test list; go back to step 2 until the list is empty.

Commits: one commit per green cycle (test + code, subject `Test: <scenario>`), one separate commit per approved refactor (subject `Refactor: <what>`).

## Workflow

1. Pick the next roadmap item whose `Depends on` items are done.
2. Consult the user: walk through the item's Proposal and Open questions, and write the Test list together (Canon TDD step 1). Update the file with the answers. When the user approves, set `Status: spec-approved` and add a dated line to its Approval log. New decisions become new ADRs. Commit the approved roadmap file on `main`.
3. `git worktree add workspaces/<NN-slug> -b roadmap/<NN-slug> main`; set `Status: in-progress` in the worktree's copy of the roadmap file (the copy on `main` stays `spec-approved` until merge). See [workspaces/README.md](workspaces/README.md).
4. Give the user a startup prompt for the workspace agent: the roadmap file path, the worktree path as its only writable area, the current state (which test-list items are done, what is pending), the Canon TDD rules, the per-cycle checkpoints, and "do not merge". The main-tree agent does not start omp instances ([ADR 0011](docs/decisions/0011-one-omp-instance-per-workspace.md)).
5. The user starts a new omp instance in the repository root and hands it the prompt. The workspace agent edits files only inside `workspaces/<NN-slug>` and runs git only against its branch from inside the worktree (`git -C workspaces/<NN-slug> …`); it never touches the main working tree, `main`, or other branches. When the user asks, it may rebase its own branch onto `main` (for example to pick up a newly merged item) and resolve the conflicts; it never rewrites `main` or another branch. It runs the TDD cycles with the user directly: it proposes the next test and waits for confirmation, performs one cycle (Canon steps 2–3) and commits, then asks the refactor question with concrete suggestions and does only approved refactors as separate commits. It reports discovered scenarios at each checkpoint and ticks the test list in the worktree's copy of the roadmap file. It never decides refactors or the next test itself.
6. Independent items (no dependency between them) may progress in parallel workspaces, each in its own omp instance with its own per-cycle checkpoints. The instance's tools come from the root `devenv.nix`; checks of a devenv change made on a workspace branch are run by the user inside the worktree. Branch-only `TEST_*` fixtures are passed explicitly by their `/nix/store` paths ([workspaces/README.md § Environment for workspace agents](workspaces/README.md#environment-for-workspace-agents)).
7. When the test list is done, the workspace agent sets `Status: in-review` in the worktree's roadmap file and stops. The main-tree agent presents the full diff (`git diff main...roadmap/<NN-slug>`) and test output to the user. Changes requested → a new startup prompt for the workspace (step 4).
8. On approval: rebase the branch onto `main` in the worktree, run `git merge --ff-only roadmap/<NN-slug>` in the main tree, move the spec and final test list into `docs/specs/<area>.md`, delete the roadmap file, update `roadmap/README.md`, and remove the worktree and branch.

## Testing

- Backend: Kotest (`BehaviorSpec`) + `kotlinx-coroutines-test` (`runTest`, virtual time for engine/queue tests). Tests that spawn real processes (from item 05 on) are tagged `Integration` (Kotest `Tag`, ADR 0021); they are excluded from `gradle test`/`gradle check` and run separately with `gradle integrationTest`. HTTP route tests use Ktor `testApplication`.
- Frontend: Vitest + React Testing Library (jsdom) (+ MSW for API mocks from the first API item on).
- Formatting is part of the standard commands (ADR 0015): see [Commands](#commands).

## Commands

Run backend commands from `backend/`, frontend commands from `frontend/`.

Backend (Gradle 8.14 via devenv, no wrapper — ADR 0014):

- `gradle check` — formatting gate (`ktlintCheck`) + all unit tests. The standard verification command.
- `gradle test` — unit tests only.
- `gradle integrationTest` — Integration-tagged tests only (real processes; needs `setsid` from util-linux — ADR 0020/0021).
- `gradle ktlintFormat` — auto-format Kotlin sources.
- `gradle run` — start the server. `STT_DATA_DIR` is required (data directory holding `stt.db`, created when missing; startup fails without it — ADR 0017), e.g. `STT_DATA_DIR=/tmp/stt gradle run`. Port `STT_PORT`, default 8080; set `STT_STATIC_DIR` to a built frontend to serve the UI.

Frontend (pnpm — ADR 0012):

- `pnpm install` — install dependencies from `pnpm-lock.yaml`.
- `pnpm test` — `vitest run` + `prettier --check` over all frontend-owned files (`src`, `index.html`, `vite.config.ts`, `package.json`, `tsconfig.json`, `.prettierrc`). The standard verification command.
- `pnpm build` — `tsc --noEmit` + `vite build` (output: `frontend/dist/`).
- `pnpm dev` — Vite dev server; proxies `/api` to `http://localhost:8080`.

## Git

- Default branch `main`; feature branches `roadmap/<NN-slug>`, checked out as worktrees under `workspaces/`.
- Commit subjects: `Test: …` / `Refactor: …` inside TDD cycles; imperative subjects otherwise.
- Commit identity comes from the environment; `pull.rebase=true`.
