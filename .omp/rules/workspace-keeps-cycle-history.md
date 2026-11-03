---
name: workspace-keeps-cycle-history
description: "A workspace agent keeps every TDD cycle as its own commit — the Test:/Refactor: record is never squashed away; auxiliary commits (status, typos, formatting) may be tidied"
condition: ["git\\s+reset\\s+--soft", "git\\s+rebase\\s+-i[^\\n]*\\bsquash", "git\\s+commit[^\\n]*--amend[^\\n]*--no-edit"]
question: "Is a workspace agent erasing TDD cycle history — merging Test:/Refactor: commits into each other or into a non-cycle commit, dropping one, or rewriting one so the cycle record is lost — rather than tidying auxiliary commits (a status commit, a typo or a formatting fix) while the cycle commits stay one per cycle? The root agent's post-review PR preparation is not this rule's subject; answer no for that."
scope: ["tool:bash", "tool:eval"]
---

A workspace agent may tidy its branch: squash auxiliary commits (the `Status: in-progress` commit, a
typo, a formatting fix) or amend one of those, as long as the **TDD cycle history survives**. Every
green cycle's `Test: <scenario>` commit and every approved `Refactor: <what>` commit stays its own
commit, in order, with its own subject and content — they are what the reviewer reads. Folding one
cycle commit into another, dropping one, or rewriting one so the cycle record is lost is forbidden.

If a cleanup would touch a cycle commit, or you are unsure whether it would, leave it and flag it to
the user.

Preparing the PR — rebasing, squashing to one commit, moving the roadmap file into `docs/specs/`,
setting `Status: done` — is the **root agent's** step 8, after it has reviewed the item, and is not
this rule's subject. The workspace agent ends at `Status: in-review`, commits that state, reports the
diff and test output, and stops.
