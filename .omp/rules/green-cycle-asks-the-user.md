---
name: green-cycle-asks-the-user
description: "The next-test proposal carries the green cycle's outcomes: the make-it-right choices (or why none) and the refactor answer (or 'no refactoring suggested' with the reason); the agent asks both before proposing the next test"
condition: ["(?i)(next test|propose the next)"]
question: "Does this output propose the next test-list item (or say the next test is next) without the cycle's make-it-right outcome and refactor outcome in it — the make-it-right choices or 'nothing to improve' with the reason, and the refactor answer or 'no refactoring suggested' with the reason?"
scope: ["text"]
agents: main
repeatMode: always
---

**Who this binds**: every top-level agent that runs a TDD cycle (the root agent and each workspace
agent), at the point it proposes the next test-list item.

A green test does not end the cycle, and the next-test proposal is where a skipped checkpoint
shows. The proposal carries the cycle's outcomes, in this order:

1. **Make it right** (Canon TDD step 3's second round): the concrete improvements the new behavior
   needed — the scenario's edge cases and error paths, the contract's exact behavior, names and
   structure that make the behavior evident — and the user's choices among them, or "nothing to
   improve" with the reason the agent could suggest none. The green cycle's `Test:` commit carries
   both rounds' code.
2. **Refactor checkpoint** (step 4): the user's answer — the approved refactors as separate
   `Refactor:` commits, or "no refactoring suggested" with the reason. A refactor never mixes with
   a behavior change.

Ask both questions before proposing the next test; the user's answer — including "nothing" or "no
refactoring" — is what ends the cycle, not the agent's own judgement. If a `Test:` commit was made
before the make-it-right answer and the answer needs code, amend that cycle commit with the user's
approval (its subject and its red/green run cite stay), or add the change separately only if the
user says so; never leave the make-it-right code out of the cycle record.
