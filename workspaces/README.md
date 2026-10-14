# workspaces/

Home for per-task git worktrees. Only this README is tracked; everything else in this folder is ignored by `.gitignore`.

## Purpose

Each roadmap item is implemented in its own git worktree here, at `workspaces/<NN-slug>`, on branch `roadmap/<NN-slug>`. The work is done by one workspace agent in its own omp instance, started by the user in the repository root with a startup prompt from the main-tree agent. That agent runs the item's TDD cycles with the user, with a checkpoint after each cycle ([ADR 0011](../docs/decisions/0011-one-omp-instance-per-workspace.md)).

## Create

```sh
git worktree add workspaces/<NN-slug> -b roadmap/<NN-slug> main
cd workspaces/<NN-slug> && direnv allow   # each worktree has its own .devenv state
```

## Remove (after the branch is merged into `main`)

The branch is merged via a pull request on GitHub ([ADR 0050](../docs/decisions/0050-pr-merge-on-github.md)); after the PR lands, the root agent syncs `main` and removes the worktree and branch:

```sh
git worktree remove workspaces/<NN-slug> && git branch -d roadmap/<NN-slug>
```

## Environment for workspace agents

The omp sandbox sees only the directory it is launched in and its descendants. A worktree's `.git` is a pointer file (`gitdir: <repo>/.git/worktrees/<NN-slug>`), so an agent launched inside `workspaces/<NN-slug>` cannot run git. Workspace agents are therefore always launched in the repository root:

- The agent runs its commands with the worktree as working directory (e.g. `workspaces/<NN-slug>/backend` for Gradle) and git as `git -C workspaces/<NN-slug> …`.
- Its tools and variables come from the **root** `devenv.nix`, captured at launch. Anything the branch adds to its own `devenv.nix` (packages, `TEST_*` fixture exports) is missing from the agent's environment until the branch is merged and the session restarted.
- Branch-only fixtures: after committing the `devenv.nix`/`nix/` change, the agent asks the user to enter the worktree's devenv shell on the host once (`cd workspaces/<NN-slug> && direnv allow`), which realizes the fixtures in `/nix/store`. `/nix/store` is readable from the sandbox, so the agent locates the store paths there (or asks the user for them) and passes the variables explicitly on every run, e.g. `TEST_JFK_WAV=/nix/store/…-jfk.wav gradle integrationTest`. Store paths change when the fixture expression changes; look them up again after every such edit.
- Branch-only packages cannot be passed this way: the agent asks the user to run the affected checks inside the worktree shell ([AGENTS.md § Workflow](../AGENTS.md#workflow), step 6).

## Rules

- A workspace agent edits files only inside its own worktree and runs git only from inside it (`git -C workspaces/<NN-slug> …`).
- Never edit the main working tree from a workspace agent.
- Never nest worktrees.

The full lifecycle (spec approval, per-cycle checkpoints, review, merge) is described in [AGENTS.md § Workflow](../AGENTS.md#workflow).
