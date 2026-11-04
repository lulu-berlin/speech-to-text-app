---
name: pr-one-squashed-commit
description: "A branch the ROOT agent prepares as a PR, after reviewing it, must be rebased onto main and squashed to exactly one commit (ADR 0050); the workspace agent never squashes its TDD history"
condition: ["(?i)(open (the|a) (pr|pull request)|create the pull request|push (this|the) branch|git push)"]
question: "Is the root agent PREPARING A PR AFTER REVIEW — asking the user to push a reviewed branch, or pushing it — while that branch has more than one commit on top of `main`? A Test:/Refactor: commit inside a workspace, or the in-review report with its history intact, is not PR preparation; answer no for those."
scope: ["tool:bash", "text"]
---

**Who this binds**: the root agent, and only at PR preparation — after it has reviewed the item
(AGENTS.md workflow step 8), not at the moment a workspace agent reports `Status: in-review`. The
sequence is: the workspace agent ends at `Status: in-review` and hands the branch over **with its
TDD cycle history intact** (its `Test: …` / `Refactor: …` commits are the record, one per cycle; it
may have tidied auxiliary commits, see `workspace-keeps-cycle-history`); the root agent reviews it;
then, preparing the PR, the root agent squashes; the user pushes and opens the pull request. A
workspace agent never squashes cycle commits, and a commit sequence is not this rule's subject.

Before the prepared branch goes to the user for a PR, it must be **one commit on top of the current
`main`** (ADR 0050: "a branch with more than one commit on top of `main` is not pushed"). This
applies to every branch the root prepares — a roadmap item branch, post-merge bookkeeping, a fix.

Do this after the review, while preparing the PR, not after the user notices:

1. `git -C <worktree> rebase main` (or `--onto main <old-base> HEAD` when the branch's old base is no longer an ancestor) to put the work on the current `main`. For an approved roadmap item branch, record its finished state before the squash (AGENTS.md workflow step 8): `Status: done`, `git mv roadmap/<NN-slug>.md docs/specs/<area>.md`, `roadmap/README.md`'s row and graph node marked done, links to the roadmap file repointed, the item's `docs/known-issues.md` pointer removed. A roadmap item's PR that still has its file in `roadmap/` is not ready.
2. Collapse the branch's commits into one: `git reset --soft main` then a single `git commit -F -`, or `git rebase -i main` with one `pick` and the rest squashed. One subject that names the item or the change, imperative; the full detail belongs in that commit's body.
3. **Verify before you tell the user the branch is ready to push**: `git rev-list --count main..HEAD` must print `1`, and `git log --oneline -2` must show exactly one commit above `main`. Never hand over `git log` output, a hash, or a "push this branch" instruction while that count is greater than 1.
4. **Keep the run's own hashes in the evidence**: the squashed commit is not the commit the tests ran at. The item log and the PR body name the commits that were checked out during the red and green runs — never the squashed commit, and never a later commit with work added on top (see `red-green-cites-the-run-commit`).
