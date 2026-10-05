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

```sh
git worktree remove workspaces/<NN-slug> && git branch -d roadmap/<NN-slug>
```

## Rules

- A workspace agent edits files only inside its own worktree and runs git only from inside it (`git -C workspaces/<NN-slug> …`).
- Never edit the main working tree from a workspace agent.
- Never nest worktrees.

The full lifecycle (spec approval, per-cycle checkpoints, review, merge) is described in [AGENTS.md § Workflow](../AGENTS.md#workflow).
