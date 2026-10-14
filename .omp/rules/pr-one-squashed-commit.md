---
name: pr-one-squashed-commit
description: "A branch handed over for a PR must be rebased onto main and squashed to exactly one commit — never two (ADR 0050)"
condition: ["git commit[\\s\\S]*git commit", "(?i)(open the PR|open a pull request|create the pull request|push .*fix/|push .*roadmap/)"]
question: "Does this output create a second commit on a branch, or hand a branch over for a PR (push/open-the-PR instruction or a commit sequence) while that branch has more than one commit on top of `main` — i.e. without rebasing onto `main` and squashing to a single commit?"
scope: ["tool:bash", "text"]
---

Before a branch is handed over for a PR, it must be **one commit on top of the current `main`** (ADR 0050: "a branch with more than one commit on top of `main` is not pushed"). The rule applies to **every** branch — a roadmap item branch, post-merge bookkeeping, a fix.

Do this in the same turn you prepare the handover, not after the user notices:

1. `git -C <worktree> rebase main` (or `--onto main <old-base> HEAD` when the branch's old base is no longer an ancestor) to put the work on the current `main`.
2. Collapse the branch's commits into one: `git reset --soft main` then a single `git commit -F -`, or `git rebase -i main` with one `pick` and the rest squashed. One subject that names the item or the change, imperative; the full detail belongs in that commit's body.
3. **Verify before you write the handover message**: `git rev-list --count main..HEAD` must print `1`, and `git log --oneline -2` must show exactly one commit above `main`. Never hand over `git log` output, a hash, or a "push this branch" instruction while that count is greater than 1 — and never create two commits in one call (`&&`-chained or consecutive `git commit`s) on a branch that is about to become a PR.

If you already committed more than once, fix it in the same turn (`git reset --soft main` + one commit keeps the tree; confirm the tree hash is unchanged) rather than reporting the branch as ready and waiting for the user to catch it.