# 0009 — Approval-gated Canon TDD workflow

Status: accepted, workflow steps 4–6 replaced by [0011](0011-one-omp-instance-per-workspace.md) · Date: 2026-10-05

## Context
The app is built by AI agents. The user wants every change approved, every spec and decision documented, and development driven by Kent Beck's Canon TDD (https://newsletter.kentbeck.com/p/canon-tdd), with the user involved in writing the test list and deciding every refactor. Parallel agent work must not disturb the main working tree.

## Decision
The authoritative full text of the rules is [AGENTS.md](../../AGENTS.md) (§ Canon TDD and § Workflow). In summary:

Canon TDD:
1. Test list, written with the user: behavioral scenarios (basic, edge, failure, must-not-break) as `- [ ]` checkboxes in the roadmap item's `## Test list`; no implementation design; approved together with the spec.
2. Exactly one test: the agent proposes the next list item with a one-line reason, the user confirms; only that item becomes one concrete, runnable test with real assertions; run it and record the red output. Never convert several items ahead of time.
3. Make it pass, for real: this and all previous tests pass. Forbidden: deleting or weakening assertions, copying computed values into expected values (expected values come from hand calculation, the oracle's logic, or the spec), refactoring while going green. Discovered scenarios are appended as `(discovered)` and confirmed with the user; if a discovery invalidates the work, ask the user whether to push on or restart with a different test order.
4. Refactor checkpoint, always ask: after every green, ask the user whether to refactor, with concrete suggestions or "no refactoring suggested" with the reason. Refactor only what is approved, keep tests green, never mix refactoring with behavior change, no early abstraction.
5. Tick the item; repeat from step 2 until the list is empty.
- Commits: one per green cycle (`Test: <scenario>`), one per approved refactor (`Refactor: <what>`).

Workflow:
1. Pick the next roadmap item whose dependencies are done.
2. Consult the user on Proposal and Open questions, write the test list together; on approval set `Status: spec-approved`, log it, record new decisions as ADRs, commit on `main`.
3. Create a worktree `workspaces/<NN-slug>` on branch `roadmap/<NN-slug>`; set `Status: in-progress` there.
4. Spawn one subagent per single TDD cycle (one test or one approved refactor) in that worktree; it commits, updates the test list, and stops without merging.
5. The subagent reports test name, red and green output, diff summary, refactor suggestions, discovered scenarios, and a proposed next test; it never decides refactors or the next test.
6. The orchestrating agent relays to the user and spawns the next cycle with the user's answer, until the list is empty. Independent items may progress in parallel worktrees.
7. Present the full diff and test output; set `Status: in-review`; requested changes go back to step 4.
8. On approval: rebase, fast-forward merge into `main`, move spec and test list to `docs/specs/<area>.md`, delete the roadmap file, update `roadmap/README.md`, remove worktree and branch.

## Consequences
- No code reaches `main` without explicit user approval; agents never merge, push, or rewrite `main` on their own.
- Progress is slower per cycle but every test, refactor, and merge is user-reviewed and individually committed.
- Roadmap files carry status and a dated approval log; finished specs move to `docs/specs/`.
- `workspaces/` holds per-item git worktrees and is ignored by git except its README.
