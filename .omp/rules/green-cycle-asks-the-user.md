---
name: green-cycle-asks-the-user
description: "A green cycle asks the user both questions before the next test: the make-it-right proposals (or, if none, how they would like to improve) and the refactor checkpoint (concrete suggestions, or 'no refactoring suggested' with the reason)"
condition: ["(?i)(\\bgreen\\b|tests? pass|Test:|next test|test[- ]list item)"]
question: "Is the agent moving past a green cycle — reporting the test green, committing a Test: cycle, or proposing the next test — without the make-it-right question (propose the concrete improvements, ask which to make; if none, ask how) and the refactor question (concrete suggestions, or 'no refactoring suggested' with the reason) being asked?"
scope: ["text", "tool:eval"]
agents: main
repeatMode: always
---

**Who this binds**: every top-level agent that runs a TDD cycle (the root agent and each workspace
agent), at the point a cycle goes green or its `Test:` commit is made.

A green test does not end the cycle. Two user decisions follow, in this order, before you propose
the next test:

1. **Make it right** (Canon TDD step 3's second round): propose the concrete improvements the new
   behavior needs — the scenario's edge cases and error paths, the contract's exact behavior, names
   and structure that make the behavior evident — and ask the user which to make. If you can suggest
   none, say so and ask how they would like to improve. Make only what the user chooses; keep every
   test green; the green cycle's `Test:` commit carries both rounds' code.
2. **Refactor checkpoint** (step 4): ask the user whether to refactor, with concrete suggestions
   (duplication to remove, names to improve, extractions, simplifications, test readability) — or
   state "no refactoring suggested" and why. Refactor only what the user approves, as separate
   `Refactor:` commits; never mix a refactor with a behavior change.

The user's answer — including "nothing" or "no refactoring" — is what ends the cycle, not your own
judgement that there is nothing to improve. The refactor question is the one most often skipped:
ask it every time, even when the code looks clean, and give the reason when you suggest nothing.

If the `Test:` commit is already made and the make-it-right answer needs code, amend that cycle
commit with the user's approval (its subject and its red/green run cite stay as they are), or add
the change in a separate commit only if the user says so; never leave the make-it-right code out of
the cycle record.
