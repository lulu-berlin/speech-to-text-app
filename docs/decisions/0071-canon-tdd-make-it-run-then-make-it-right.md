# 0071 — Canon TDD's step 3 has two rounds: make it run, then make it right

Status: accepted · Date: 2026-11-21

## Context

The workflow follows Kent Beck's Canon TDD (<https://newsletter.kentbeck.com/p/canon-tdd>) as
[0009](0009-approval-gated-canon-tdd-workflow.md) records, with the authoritative text in
[AGENTS.md](../../AGENTS.md) § Canon TDD. Step 3 there is "Make it pass, for real": change the code
so the new test and all previous tests pass; step 4 then asks the refactor question.

Canon TDD's step 3 is two rounds: **make it run, then make it right**. The first is the smallest,
simplest change that turns the test green; the second makes that new behavior right. The workflow
named neither round, so a green could be read as the end of step 3 — either the final shape was
written first, or the new code's correctness and edge cases were left for the step-4 refactor.
Step 4 is a different thing: behavior-preserving cleanup of everything the change touched, proposed
to the user and committed separately. Deferring step 3's correctness to step 4 mixes behavior work
into refactor commits, which must never change behavior, and calling the first green "done" leaves
the scenario satisfied only to the letter of its assertion. The round is also a user decision: the
workflow is approval-gated, and the new behavior's improvement is part of what the user approves.

## Decision

Step 3 has two rounds, in this order:

1. **Make it run.** The smallest, simplest change that turns the test green and keeps every earlier
   test green. No design ahead, no cleanup; crude, duplicated or special-cased code is acceptable
   if it is the shortest path to green.
2. **Make it right.** With the test green, the agent proposes to the user the concrete improvements
   the new behavior needs — the scenario's edge cases and error paths, the contract's exact
   behavior, and names and structure that make the behavior evident — and asks which to make; if it
   can suggest none, it says so and asks the user how they would like to improve. The agent makes
   only what the user chooses, keeping every test green. The scenario is not done until its behavior
   is right, not merely green.

The make-it-right round is the user's, not the agent's: each cycle includes the proposals the user
chooses from, or the question when the agent has none. It is still step 3 and not step 4's refactor
checkpoint: behavior-preserving cleanup of everything the change touched (duplication, extraction,
simplification, test readability) stays a separate, user-approved commit after the cycle's green,
and a refactor commit never carries a behavior change.

The green cycle's one commit carries the test and both rounds' code; each approved refactor is its
own commit. The tests remain the arbiter: no round deletes or weakens an assertion, and no round
copies a computed value into an expected value.

## Consequences

- The workspace agent shows the first green's simplest change and then the make-it-right round; each
  cycle includes a proposal the user chooses from, or a question when the agent has none; a green
  that only satisfies the assertion's letter does not end the cycle.
- The step-4 refactor question and its separate commits are unchanged; "make it right" is not an
  excuse to refactor without the user's approval.
- [AGENTS.md](../../AGENTS.md) § Canon TDD step 3 and the commit line, and
  [0009](0009-approval-gated-canon-tdd-workflow.md)'s status, are amended; the workspace startup
  prompts point at AGENTS.md, so they need no change.
- A cycle may take one more round and one more user decision; the per-cycle checkpoints still bound
  it.
- A TTSR rule (`green-cycle-asks-the-user`,
  [`.omp/rules/`](../../.omp/rules/green-cycle-asks-the-user.md)) reminds the agent at the
  green/commit point that the make-it-right proposals and the refactor question are the user's; it
  is a warning, not an interrupt.
