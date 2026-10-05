# 0011 — One omp instance per roadmap workspace

Status: accepted · Date: 2026-10-05

## Context
ADR 0009 had the orchestrating agent spawn one subagent per TDD cycle inside its own session. The user wants each roadmap workspace worked on by its own agent in its own omp instance, started by the user.

## Decision
Replaces workflow steps 4–6 of ADR 0009. The Canon TDD rules and the other workflow steps are unchanged.
- The main-tree agent consults the user, gets the spec approved, creates the worktree (`workspaces/<NN-slug>`, branch `roadmap/<NN-slug>`), and gives the user a startup prompt for the workspace agent.
- The user starts a new omp instance in the repository root and hands it that prompt. The main-tree agent does not start omp instances.
- The workspace agent works only inside `workspaces/<NN-slug>`: it edits files only there and runs git only against its own branch, from inside the worktree (`git -C workspaces/<NN-slug> …`). It never touches the main working tree, `main`, or other branches, and never merges or pushes.
- The workspace agent runs all TDD cycles of the item directly with the user. The per-cycle checkpoints (next test, refactor question, discovered scenarios) happen in that session.
- When the test list is done, the workspace agent sets `Status: in-review` and stops. Review and merge (workflow steps 7–8) go back to the main-tree agent.

## Consequences
- The orchestrator no longer relays every cycle; the user talks to each workspace agent directly.
- Starting at the repository root gives the instance access to the shared `.git`, so commits on the worktree branch work.
- The instance's tools come from the root `devenv.nix` (the `main` version), not the worktree's. A tool added on a workspace branch is not available to that agent until it is merged; checks of a changed devenv environment are run by the user inside the worktree.
- Confinement to the worktree is by instruction, not by the sandbox. The startup prompt must state it, together with the roadmap file, the current state, and the rules.
