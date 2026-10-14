# 0050 — Roadmap items merge via human pull requests on GitHub; the root agent never merges

Status: accepted, amends AGENTS.md § Workflow step 8 · Date: 2026-10-14

## Context
The workflow so far (ADR 0009, AGENTS.md § Workflow step 8): the workspace agent implements an
item on `roadmap/<NN-slug>`; the root agent reviews the full diff and, on user approval, rebases
the branch onto `main` in the worktree and runs `git merge --ff-only` — so every landing item
advances `main` directly inside the repository, with no GitHub record.

The repository rule already says agents never merge, push, or rewrite `main` on their own
(AGENTS.md, Non-negotiable rules); the `git merge --ff-only` was the remaining agent-driven write
to `main`. The user decided (2026-10-14) that every merge to `main` must happen on GitHub through
a pull request the human creates: GitHub keeps the review, checks and audit trail, and once item
51 lands, CI gates PRs automatically.

## Decision
- **The root agent reviews, it does not merge.** After the workspace agent finishes an item and
  sets `Status: in-review`, the root agent presents `git diff main...roadmap/<NN-slug>` and the
  test output as today (AGENTS.md step 7). Changes requested → a new workspace session, unchanged.
- **Every branch pushed as a PR is one commit.** The root agent rebases the branch onto the current
  `main` in the worktree and squashes it to a single commit (subject names the item, or the change,
  imperative) before handing it over — a roadmap item branch, post-merge bookkeeping, a fix alike.
  A branch with more than one commit on top of `main` is not pushed.
- **On approval a roadmap item branch also carries the item's finished state in that commit**: the
  roadmap file's `Status: done`, its spec and final test list in `docs/specs/<area>.md`, the
  roadmap file deleted, and `roadmap/README.md`'s row and graph node updated. The merge is what
  makes the state final.
- **The human ships it**: only the human pushes code to GitHub. The user pushes the branch,
  creates the pull request, and merges it on GitHub (the branch is a single commit on top of
  `main`, so any PR merge strategy lands one commit).
- **Agents are read-only on GitHub**: the repository is public — agents may view whatever is
  publicly accessible, but make no changes: no pushes (not the branch, not `main`), no pull
  request creation or commenting, no merges. Every GitHub-side mutation is performed by the human
  (or by CI, which runs on the human's pushes).
- **After the PR lands**: the root agent syncs `main` with `git pull --ff-only` (applying GitHub's
  result, not merging) and removes the worktree and branch (workspaces/README.md § Remove); the
  spec move and the index update already rode in the PR's commit.
- Applies to every roadmap item from now on, including the in-progress workspaces (44, 45, 50,
  51); their approvals remain valid — only the landing mechanism changes.
- Review feedback on the open PR that requires code changes follows the existing loop (back to the
  workspace, re-review) or is committed by the user directly on the branch before merging, at the
  user's discretion.

## Consequences
- Every landing PR is exactly one commit on `main` — the squash requirement covers bookkeeping and
  fix branches too — and every merge is a GitHub pull request the human controls; the per-cycle
  `Test:`/`Refactor:` commits live only on the workspace branch until the squash. The final test
  list stays the record in `docs/specs/`.
- Once item 51's CI lands, PR checks gate merges automatically instead of relying on local runs.
- Local `main` never diverges from `origin/main` beyond GitHub's own merge, so the post-merge
  sync stays a clean fast-forward; the `git pull --ff-only` is bookkeeping, not an agent merge —
  it fetches public origin and changes only the local clone.
- Agents have no write path to GitHub at all: the only GitHub-side actors are the human and CI.
- The human is the last gate for every item; item-approval diffs shown by the root agent and the
  PR are the same tree, so no double review is implied.