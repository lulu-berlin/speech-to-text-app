# 0062 — Settings save themselves, and a reset restores the server's defaults

Date: 2026-10-27
Status: accepted

## Context

Item 32's Settings page stored on an explicit Save. That step had three costs: a failed write showed
its rejection in a page-level callout, one step away from the field that caused it; a fast second
click sent a second `PUT` while the first was in flight; and the page had to serialize `threads`
itself, so invalid or truncated input reached the contract. The user asked (2026-10-27) for the
opposite: no Save, every field saving as it changes, and "Restore default settings" where Save was.

Two constraints shape the answer. A `PUT /api/settings` carries the settings as a whole (the
contract has no per-field write), so per-field saving means whole-settings writes that can race.
And the defaults exist only as the seed of `V6__settings.sql`, mirrored by
`docs/api/settings-updated.defaults.json`; no endpoint means "reset".

## Decision

- **No Save button.** A select or switch commits when it changes; a typed field — threads, speaker
  count, max speakers — commits when the input itself loses focus, or on Enter. Typing never writes,
  so an intermediate `""` or `"-"` is never stored, and an empty threads field is the contract's
  `null` (whisper's default).
- **One write in flight, later commits coalesced.** A change made while a write runs collapses into a
  single follow-up carrying the latest values; the restore request joins the same queue. The page
  shows "Saving…" in a polite live region until the answer, so a write is never silent.
- **Invalid input is never sent.** A typed field outside its contract bound (threads `>= 1`; speaker
  count and max speakers `1..20`) shows a field error and writes nothing; the silent coercion of the
  speaker counts (`clampCount`) goes, since under auto-save it would store a value nobody typed.
- **A rejected write puts the field its code names back to its last stored value** and shows the
  server's message on it (`invalid-device`, `invalid-threads`, `invalid-language`, `invalid-speakers`,
  `invalid-max-speakers`); a code naming no field uses the page's callout. With one write per field
  change, that value is what the previous field's write stored. The rejected snapshot is dropped and
  every other field keeps its draft: the next write is built from the form as it stands, so a value
  the rejected write also carried is stored then and nothing stale is retried.
- **Another tab's or the engine's `settings-updated` applies to the fields**, except the field that
  currently has focus (the user's draft is kept) and a field holding an invalid value; the stored
  baseline always follows.
- **`DELETE /api/settings` resets the stored settings to the defaults** — `auto`, `null`, `auto`,
  `auto`, `null`, `8` — in one write that also clears `deviceFallback`; no body, no refusals,
  idempotent; answers `200` Settings and emits `settings-updated`, like the other writes. The
  defaults are a Kotlin constant in `stt.db`, whose values a route spec pins against the V6 seed.
- **"Restore default settings" asks first**, through the existing confirmation dialog and
  confirm machine; cancelling sends nothing.
- **The e2e journeys that clicked Save are rewritten** to the auto-save flow — `17-settings`,
  `18-macos-metal` and the desktop helper (ADR 0046: a feature's journey follows the feature).
- **2026-10-28 — An answer or a load cannot overwrite a newer `settings-updated`.** A write (`PUT`
  or the reset `DELETE`), or a fallback dismissal, whose answer is superseded by a
  `settings-updated` received while it ran leaves the event-derived settings in place: it still
  clears the writing flag and the queued body and still starts the queued follow-up, but does not
  apply its answer (a write's answer still clears the error; a dismissal does not). The stored
  baseline therefore always follows the event, whatever order the answer and the event arrive in
  (the server emits `settings-updated` for every write). A `settings-updated` received before the
  settings load is answered is kept and laid over the fetch answer when it lands, since the event
  is the newer settings; a shown write rejection stays until the user answers it — neither a
  `settings-updated` nor a dismissal clears it.

## Consequences

- Item 32's Save flow, and the wording of its spec's test list and cycles, are superseded; that spec
  keeps them as history with a note pointing here and at item 72's spec.
- The frontend no longer coerces typed values; the server's validation is the only authority, and its
  codes are rendered on the field they name.
- The defaults live in two places — `V6` for a fresh database, the Kotlin constant for the reset.
  Changing a default requires both; the route spec's pinned values catch a reset that drifts.
- A write is now implicit, so the page needs the "Saving…" signal and field-level errors to stay
  honest; the error callout remains for load failures and for rejections that name no field.
- The reset needs no new server state and is safe to repeat; it is destructive to the user's
  settings, which is why it is confirmed.
