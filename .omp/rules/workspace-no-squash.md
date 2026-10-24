---
name: workspace-no-squash
description: "Workspace agents never squash or collapse a roadmap branch's TDD commits; the root agent prepares the squashed PR"
condition: ["git\\s+reset\\s+--soft", "git\\s+rebase\\s+-i[^\\n]*\\bsquash", "git\\s+commit[^\\n]*--amend[^\\n]*--no-edit"]
scope: ["tool:bash", "tool:eval"]
---

A workspace agent must not collapse its branch history. Every green TDD cycle and every approved refactor stays its own commit (`Test: …`, `Refactor: …`); the reviewer needs them. Do not run `git reset --soft`, a squashing interactive rebase, or amend-away cycle commits on a roadmap branch. Preparing the PR — rebasing, squashing to one commit, moving the roadmap file into `docs/specs/`, setting `Status: done` — is the root agent's step 8. The workspace agent ends at `Status: in-review`, commits that state, reports the diff and test output, and stops. If the PR rule seems to require a squash, flag it to the user instead of doing it.