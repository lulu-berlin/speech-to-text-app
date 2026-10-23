# AGENTS.md

## Project

A web app that transcribes uploaded audio files with speaker diarization. Backend: Kotlin + Ktor + SQLite; frontend: TypeScript + React + styled-components, served by the backend; one monorepo. Users upload many files into a work queue that is processed one task after another; the queue can be paused, each task can be paused or canceled, and tasks can be reordered. The queue and all transcription output are stored in the database, and past transcriptions stay viewable. All async work runs on Kotlin coroutines. Dependencies come from devenv; the final deliverable is a Nix flake derivation that runs backend, database, frontend, and the whisper model. `docs/oracle/transcribe.py` is the behavioral oracle for transcription, diarization, and output formats.

- Pending work: [roadmap/README.md](roadmap/README.md)
- Decisions: [docs/decisions/](docs/decisions/README.md)

## Non-negotiable rules

- **No change reaches `main` without the user.** Specs and test lists are approved before coding; every refactor is approved individually; diffs are approved before merging. **Agents never commit to `main`** — not even a roadmap or docs change: every change is committed on a branch and handed to the user, who pushes it, creates the pull request, merges it and updates local `main`. Agents never push to GitHub and make no changes there: the repository is public, so agents may only view what is publicly accessible ([ADR 0050](docs/decisions/0050-pr-merge-on-github.md)).
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
docs/decisions/  docs/specs/  docs/conventions/  docs/api/ (API contract examples, ADR 0029)  docs/oracle/transcribe.py (reference oracle)  docs/known-issues.md (living backlog of open findings; an issue leaves it the moment a roadmap item plans it)
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

1. Pick the next roadmap item whose `Depends on` items are done; a dependency marked "spec-approved only" needs just its approved spec ([ADR 0029](docs/decisions/0029-contract-first-frontend-shared-api-examples.md)).
2. Consult the user: walk through the item's Proposal and Open questions, and write the Test list together (Canon TDD step 1). Update the file with the answers. When the user approves, set `Status: spec-approved` and add a dated line to its Approval log. New decisions become new ADRs. Commit the approved roadmap file on a `docs/<slug>` branch and hand the branch to the user for a PR — never commit on `main`.
3. Once the docs PR with the approved spec has merged, `git worktree add workspaces/<NN-slug> -b roadmap/<NN-slug> main`; set `Status: in-progress` in the worktree's copy of the roadmap file (the file on `main` stays `spec-approved` until the item's PR merges). See [workspaces/README.md](workspaces/README.md).
4. Give the user a startup prompt for the workspace agent: the roadmap file path, the worktree path as its only writable area, the current state (which test-list items are done, what is pending), the Canon TDD rules, the per-cycle checkpoints, and "do not merge". The main-tree agent does not start omp instances ([ADR 0011](docs/decisions/0011-one-omp-instance-per-workspace.md)).
5. The user starts a new omp instance in the repository root and hands it the prompt. The workspace agent edits files only inside `workspaces/<NN-slug>` and runs git only against its branch from inside the worktree (`git -C workspaces/<NN-slug> …`); it never touches the main working tree, `main`, or other branches. When the user asks, it may rebase its own branch onto `main` (for example to pick up a newly merged item) and resolve the conflicts; it never rewrites `main` or another branch. It runs the TDD cycles with the user directly: it proposes the next test and waits for confirmation, performs one cycle (Canon steps 2–3) and commits, then asks the refactor question with concrete suggestions and does only approved refactors as separate commits. It reports discovered scenarios at each checkpoint and ticks the test list in the worktree's copy of the roadmap file. It never decides refactors or the next test itself.
6. Independent items (no dependency between them) may progress in parallel workspaces, each in its own omp instance with its own per-cycle checkpoints. The instance's tools come from the root `devenv.nix`; checks of a devenv change made on a workspace branch are run by the user inside the worktree. Branch-only `TEST_*` fixtures are passed explicitly by their `/nix/store` paths ([workspaces/README.md § Environment for workspace agents](workspaces/README.md#environment-for-workspace-agents)).
7. When the test list is done, the workspace agent sets `Status: in-review` in the worktree's roadmap file and stops (it does not set `done`; the root agent's squash commit does, step 8). The main-tree agent presents the full diff (`git diff main...roadmap/<NN-slug>`) and test output to the user. Changes requested → a new startup prompt for the workspace (step 4).
8. On approval: the root agent does not merge. It prepares the PR in the worktree: it rebases the branch onto `main` and squashes it to a single commit that already carries the item's finished state (ADR 0050) — the item is marked done **when the PR is prepared, not after the merge**: the roadmap file's `Status: done (roadmap item <NN>, approved <date>)`, its spec and final test list moved with `git mv` into `docs/specs/<area>.md` (the roadmap file is gone from `roadmap/`), `roadmap/README.md`'s row (status `done`, link to the spec) and graph node (`✓`, `:::done`) updated, any pending item that linked the roadmap file repointed at the spec, and `docs/known-issues.md`'s pointer to the item removed. It hands the branch to the user; the user pushes it, creates the pull request, and merges it on GitHub. Once the PR is merged, local `main` is fast-forwarded (`git pull --ff-only`) and the worktree and branch are removed; nothing is ever committed on `main`.

## Known issues backlog

Keep [docs/known-issues.md](docs/known-issues.md) current: add every real finding (from a review, a test failure, or a user report) as it appears, with severity and `file:line` evidence; **remove** an issue the moment a roadmap item is created that handles it — it is then planned work tracked by that item. The file's Maintenance rules are the authority; the backlog is never a roadmap item itself.

## Testing

- Backend: Kotest (`BehaviorSpec`) + `kotlinx-coroutines-test` (`runTest`, virtual time for engine/queue tests). Tests that spawn real processes (from item 05 on) are tagged `Integration` (Kotest `Tag`, ADR 0021); they are excluded from `gradle test`/`gradle check` and run separately with `gradle integrationTest`. HTTP route tests use Ktor `testApplication`.
- Frontend: Vitest + React Testing Library (jsdom) (+ MSW for API mocks from the first API item on).
- End-to-end: Playwright in the top-level `e2e/` package against the real backend (item 21). Every item that adds or changes an app feature includes at least one e2e test of it in its test list and approval; build, packaging and refactor-only items are exempt and say why ([ADR 0046](docs/decisions/0046-every-feature-has-an-e2e-test.md)).
- Formatting is part of the standard commands (ADR 0015): see [Commands](#commands).

## Commands

Run backend commands from `backend/`, frontend commands from `frontend/`.

Root (devenv):

- `devenv up` — start the whole app for development: the frontend (`pnpm dev`, Vite dev server with HMR on port 5173, proxying `/api` to the backend) and the backend (Ktor on 8080; rebuilt and restarted when backend sources change; `STT_DATA_DIR` comes from the devenv shell). No browser opens; the Vite URL and both processes' logs are printed. Stop with Ctrl-C, then `q` to confirm — or `devenv down`. Dev data (`stt.db`, uploads) lives in `.devenv/state/stt`, a fixed, git-ignored location that survives restarts. Changes to `devenv.nix` need `direnv reload` to take effect.

Backend (Gradle 9.8 via devenv — ADR 0049; agents use the devenv `gradle`, not the root `./gradlew`, which exists for human developers without Nix — ADR 0014 as scoped by 0049):

- `gradle check` — formatting gate (`ktlintCheck`) + all unit tests. The standard verification command.
- `gradle test` — unit tests only.
- `gradle integrationTest` — Integration-tagged tests only (real processes; needs `setsid` from util-linux — ADR 0020/0021).
- `gradle ktlintFormat` — auto-format Kotlin sources.
- `gradle run` — start the server. `STT_DATA_DIR` is required (data directory holding `stt.db`, created when missing; startup fails without it — ADR 0017), e.g. `STT_DATA_DIR=/tmp/stt gradle run`. `STT_SPEAKER_MODEL` (CAM++ ONNX model, ADR 0003) and `STT_WHISPER_MODEL` (whisper model, ADR 0008) are required too; the devenv shell sets both. At startup the server asks whisper-cli once which GPUs it can use (item 32). The processing engine starts with the server and takes the queue; the speaker model loads on the first diarization. Port `STT_PORT`, default 8080; set `STT_STATIC_DIR` to a built frontend to serve the UI. The compute device (`auto`, `cpu` or a reported GPU) and the thread count of whisper-cli and the speaker model are settings stored in the database, changed on the Settings page or with `PUT /api/settings` (item 32); a change applies from the next task.

Frontend (pnpm — ADR 0012):

- `pnpm install` — install dependencies from `pnpm-lock.yaml`.
- `pnpm test` — `vitest run` + `prettier --check` over all frontend-owned files (everything in `frontend/` except what `frontend/.prettierignore` excludes: `dist`, `pnpm-lock.yaml`; plus the root `.prettierrc`). The standard verification command.
- `pnpm build` — `tsc --noEmit` + `vite build` (output: `frontend/dist/`).
- `pnpm dev` — Vite dev server; proxies `/api` to `http://localhost:8080`.

Documentation (Node, run from the repository root; run them after changing `README.md` or `docs/architecture.md`, code the overview names, or backend exception handling):

- `node docs/check-links.mjs README.md docs/architecture.md` — every relative link, `#anchor` and image resolves.
- `node docs/check-readme-windows.mjs` — `README.md` documents the Windows x64 path: the bootstrap commands, the Vulkan driver requirement, the CPU fallback, the per-user data directory and the ARM64 scope.
- `node docs/check-architecture-names.mjs && node docs/check-architecture-diagram.mjs` — every code name in the architecture overview exists in the code, and its diagram's boxes and arrows are real components and calls.
- `node docs/check-exceptions.ts` — every broad `catch` in `backend/src/main/kotlin` rethrows, logs its throwable, or carries `// exceptions: swallow-ok <reason>`, and no `runCatching` remains; `node --test docs/check-exceptions.test.ts` covers the check itself.

## Git

- Default branch `main`; feature branches `roadmap/<NN-slug>`, checked out as worktrees under `workspaces/`, and `docs/<slug>` branches for roadmap, decision and known-issue changes.
- **Nothing is committed on `main`**: every change lands on a branch and reaches `main` through a pull request the user creates and merges; the agent's local `main` only ever fast-forwards.
- Commit subjects: `Test: …` / `Refactor: …` inside TDD cycles; imperative subjects otherwise.
- Commit identity comes from the environment; `pull.rebase=true`.
