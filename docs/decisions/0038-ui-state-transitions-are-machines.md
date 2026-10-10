# 0038 — UI state transitions and form-submission flows are machines; controlled input values may stay `useState`

Status: accepted · Date: 2026-10-10

## Context

ADR 0031 decided that XState v5 machines model what the client controls and that
"the UI cannot send a command the machine does not allow". Items 14 and 15 (Frontend
queue UI, Frontend history UI) build the first interactive views, and both hit the same
question on the way: where does the machine rule stop and plain local state begin? One
workspace reached for `useState` for UI transitions — confirmation dialogs, an upload
batch's progress rows, a searchable select's open/filter state, an options panel's
advanced toggle and a submit-style flow — which competes with ADR 0031's direction
(user consultation 2026-10-10).

## Decision

- **UI state transitions and form-submission flows MUST be XState v5 machines**
  (ADR 0031), including:
  - confirmation dialogs (idle → confirming → running → done | failed),
  - upload batches (the per-file rows, completion and busy state),
  - combobox open/filter state,
  - toggles whose state is a transition target (e.g. an "advanced options" panel),
  - any submit-style flow.
- **Controlled input values** — plain form field values such as the queue options
  panel's language, speakers, speaker count and maxSpeakers — **may stay `useState`**.
  They are data, not transitions.
- Dialog **state ownership** lives in the feature machines; the shared dialog
  primitives (`ConfirmDialog`, the Radix wrappers, design-system pieces) render from
  props and never hold flow state.
- The machine rule applies to every frontend item, in-progress ones included: item 14
  (queue UI) and item 15 (history UI) rework their already-approved `useState` flows as
  machines, in `Refactor:` commits with no behavior change, their suites green cycle by
  cycle.

## Consequences

- The line is drawn by what changes: transitions and submission flows are machines;
  field values are data.
- Items 14 and 15 carry the rework; shared primitives under `src/shared/ui/` and
  `src/shared/state/` stay unchanged except for item 14's own additions (the
  `SearchableSelect` combobox machine is item 14's file under `src/shared/state/`;
  item 15 must not edit it).
- New machines are tested as pure logic (events in, states and context out) in addition
  to the RTL view tests, as ADR 0031 requires.
- This ADR records the ruling once for both workspaces; it amends ADR 0031 by making
  the boundary explicit.