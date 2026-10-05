# workspaces/

Home for per-task git worktrees. Only this README is tracked; everything else in this folder is ignored by `.gitignore`.

## Purpose

Each roadmap item is implemented in its own git worktree here, at `workspaces/<NN-slug>`, on branch `roadmap/<NN-slug>`. The work is done by a sequence of single-TDD-cycle subagents: one test or one approved refactor per subagent run, with a user checkpoint after each run.

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

- Never edit the main working tree from a worktree.
- Never nest worktrees.

The full lifecycle (spec approval, per-cycle checkpoints, review, merge) is described in [AGENTS.md § Workflow](../AGENTS.md#workflow).
