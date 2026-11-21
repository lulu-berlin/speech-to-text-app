---
name: refactor-asks-the-user
description: "The next-test proposal carries the green cycle's refactor outcome: the user's answer, or 'no refactoring suggested' with the reason"
condition: ["(?i)(next test|propose the next)"]
question: "Does this output suggest the next test without the refactor outcome in it — the user's answer, or 'no refactoring suggested' with the reason?"
scope: ["text"]
agents: main
repeatMode: always
---

**Who this binds**: every top-level agent that runs a TDD cycle (the root agent and each workspace
agent), at the next-test proposal.

The refactor checkpoint (Canon TDD step 4) is the user's: after the cycle's green (the make-it-right
round included), ask whether to refactor, with concrete suggestions (duplication to remove, names
to improve, extractions, simplifications, test readability) — or state "no refactoring suggested"
and why. Refactor only what the user approves, as separate `Refactor:` commits; a refactor never
mixes with a behavior change.

The next-test proposal carries the outcome: the approved refactors (with their commits) or "no
refactoring suggested" with the reason. The user's answer ends the cycle, not the agent's own
judgement; the refactor question is the one most often skipped.
