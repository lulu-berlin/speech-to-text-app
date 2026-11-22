---
name: make-it-right-asks-the-user
description: "A refactor suggestion or the next-test proposal carries the green cycle's make-it-right outcome: the user's choices among the proposed improvements, or 'nothing to improve' with the reason"
condition: ["(?im)^\\s*(?:\\*\\*)?(?:next test|refactor(?: suggestions?|:)|i suggest (?:a |the )?refactor)"]
question: "Does this output, as the agent's own proposal for its next step (not a prompt, a review or a quote describing one), suggest a refactor or the next test without the make-it-right outcome in it — the user's choices among the concrete improvements, or 'nothing to improve' with the reason?"
scope: ["text"]
agents: main
repeatMode: always
---

**Who this binds**: every top-level agent that runs a TDD cycle (the root agent and each workspace
agent), at the moment it suggests a refactor or the next test-list item.

Before either suggestion, the cycle's make-it-right round (Canon TDD step 3's second round) is the
user's: propose the concrete improvements the new behavior needs — the scenario's edge cases and
error paths, the contract's exact behavior, names and structure that make the behavior evident —
and ask which to make; if you can suggest none, say so and ask how the user would like to improve.
Make only what the user chooses; the green cycle's `Test:` commit carries both rounds' code.

The refactor suggestion and the next-test proposal carry the outcome: the user's choices, or
"nothing to improve" with the reason. If a `Test:` commit was made before the answer and the answer
needs code, amend that cycle commit with the user's approval (its subject and its red/green run
cite stay), or add the change separately only if the user says so; never leave the make-it-right
code out of the cycle record.
