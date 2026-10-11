# Follow mode stops by itself (hold-off race)

Status: done (roadmap item 36, merged 2026-10-11)
Depends on: [15 — History UI](frontend-history-ui.md) (done), [21 — Frontend–backend end-to-end](frontend-backend-end-to-end.md) (done; the `e2e/` suite, ADR 0046)
Blocks: [35](35-readme-and-architecture-overview.md) (its playback GIF shows follow mode)

## Goal

While a transcript plays, follow mode keeps the playing segment in view until the user scrolls by hand — it never stops on its own.

## Already decided

- Found in item 35 (2026-10-11) while recording the playback GIF: "Back to playing position" appeared with no hand scroll, and the view stopped following. The user chose to fix the app before recording follow mode for the README.
- Cause (`frontend/src/features/history/TaskTranscriptView.tsx`, the follow effect; `followModeMachine.ts`): the follow effect sets `holdOffFollowScrollRef`, calls `active.scrollIntoView({ block: 'nearest' })` and clears the hold-off in `setTimeout(…, 0)`. Browsers fire the `scroll` event in the next rendering step, which can come after that timer; `handleScroll` then sends `SCROLLED`, and `following` → `stopped`.
- Evidence: a throwaway Chromium page mirroring that code (flag, `scrollIntoView` in a scrollable box, clear on `setTimeout(0)`) — in 40 trials the scroll event arrived after the flag was cleared **21 times**. The e2e check in `06-playback.spec.ts` ("following must hold through its own scroll") has passed by timing.
- [ADR 0038](../decisions/0038-ui-state-transitions-are-machines.md): follow mode stays a machine; [ADR 0046](../decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e test.
- Item 31 (the sticky header) is merged before this item starts; its `scroll-margin-top` on the active segment changes where the follow scroll lands, and the fix must work with that offset in place — there is no ordering choice left.

## Proposal (settled in the consultation, 2026-10-11)

- **Input-event detection**: the only things that stop following are user input that scrolls — `wheel`, `touchmove`, keyboard (PageUp/PageDown/Space/arrows) and **scrollbar drag** (which needs its own pointer/mousedown detection on the region — called out in the draft, still required). `scroll` events alone never stop following; the view's own `scrollIntoView` scroll is simply one of them.
- No timer-based hold-off: `holdOffFollowScrollRef` and its `setTimeout(0)` are removed.
- The machine keeps its shape (ADR 0038): `SCROLLED` is sent only when input evidence is seen; "Back to playing position" stays `RESUME`.

## Open questions (answered 2026-10-11)

- Which mechanism (position match vs. input events)? → **Input-event detection** (user). Position match was the other candidate: compare the follow scroll's resulting positions against the event — rejected for this pass because input-event detection needs no position bookkeeping and covers the user-visible cases directly.
- Does item 31 (sticky header, follow offset below the header) change the follow scroll, so this item should land first or together? → **Resolved**: item 31 already landed; the fix must work with the `scroll-margin-top` offset present (it does not affect the mechanism — scrollbar/input detection is independent of where the follow scroll lands).

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user:

- [x] Following holds through its own scroll even when the scroll event arrives after the next macrotask (the race reproduced deterministically in the component test)
- [x] Following holds through many consecutive follow scrolls while playing
- [x] A hand scroll (wheel) still stops following and shows "Back to playing position"
- [x] A hand scroll by dragging the scrollbar / keyboard still stops following
- [x] "Back to playing position" resumes following and scrolls to the playing segment
- [x] (e2e, ADR 0046) Against the real backend: a long transcript plays through many follow scrolls and "Back to playing position" never appears without a hand scroll; a hand scroll still shows it
- [x] (must not break) Existing transcript view tests and the e2e playback journey stay green

## Acceptance criteria

- Follow mode never stops without a hand scroll, in the browser, across many follow scrolls.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted by item 35's workspace agent from the bug found while recording the playback GIF; the user chose to fix the app before recording follow mode.
- 2026-10-11: consultation (root agent): mechanism → input-event detection (wheel, touch, keyboard, scrollbar drag); the item 31 ordering question resolved. Test list (7 items, incl. the e2e test) approved by the user; status `spec-approved`.
- 2026-10-11: item 6 (e2e) green on the user's machine (full Playwright journey against the real backend, incl. `17-follow-mode.spec.ts`); all test-list items ticked.
- 2026-10-11: merged into `main` (head `185ff394`) after the user approved the diff and confirmed the e2e green on their machine. The hold-off was replaced by input-event detection: wheel, touch, the scroll keys and a scrollbar drag (pointer-press with a moved region) stop following; bare scroll events never do, so the view's own follow scroll cannot stop it. Review-folded: the e2e fixture lines' indentation (the suite's prettier gate). Spec and final test list moved here from `roadmap/36-follow-mode-hold-off-race.md`.
