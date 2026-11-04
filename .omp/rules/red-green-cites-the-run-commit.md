---
name: red-green-cites-the-run-commit
description: "A recorded red or green names the commit the repository was on during that run — never a later commit (a squash, a rebase, or work added on top) whose tree differs"
condition: ["(?i)(\\bred\\b|\\bgreen\\b)[\\s\\S]{0,400}(commit|[0-9a-f]{7,40})"]
question: "Does this output cite a commit for a red or green test result that is not the commit the repository was on when the test actually ran — a later squashed or rebased commit, or a commit with work added on top — instead of the exact commit of that run?"
scope: ["text"]
---

Every recorded red or green — a roadmap test-list tick, an item's log line, a review note, a PR body,
a commit message, a report to the user — names **the commit the repository was actually on when that
run happened**, and says so. That is the only commit whose result the run proves.

A later commit is not a substitute:

- **A squash or rebase rewrites the hash** even when the tree is identical. Cite the original run
  commit. If the original has to be quoted by its squashed hash, say why it is equivalent and prove
  it: the trees are identical (`git diff --stat <run> <squashed>` empty), not merely "the same
  change".
- **A commit that adds anything on top** — a follow-up fix, a version-hash renewal, a roadmap or docs
  edit, the `Status: in-review` commit — makes the tree different from the tested one. Citing it
  claims a run that never happened on that tree; cite the run commit instead, and re-run if the
  later change could affect the result.
- **A pushed or merged commit** (the PR's merged squash) is a third tree again; the run's evidence
  stays the hash the worktree was on.

If the run's commit is unknown, say the run's commit is not recorded and re-run on the current commit
rather than attaching an unverified hash.
