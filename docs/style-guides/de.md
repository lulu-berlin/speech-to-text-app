# German (de) — style guide

Companion to `docs/glossary/de.md` and `docs/reviews/de.md`; the standard is the user decision
of 2026-11-12 in `docs/specs/localization.md`.

- **Address:** informal `du` with the imperative (`Prüfe die Adresse`, `wähle es`), matching the
  English UI and the consumer products compared.
- **Gender:** no generic masculine (user decision 2026-11-12). Speaker terms are neutral:
  `Sprechende` (plural), `sprechende Person` (singular), `Anzahl der Sprechenden`, `Max.
  Sprechende`, `Trennung der Sprechenden`, `Erkennen der Sprechenden`. The compared products'
  GM wordings are recorded in the review page as evidence, never used in the catalog. The
  comparison is for idiomatic wording only: a non-gender-neutral or culturally insensitive term
  is never accepted, even when a compared product uses it.
- **Phases:** the queue line uses `am` + verbal noun (`am Aufteilen`, `am Transkribieren`, `am
  Erkennen der Sprechenden`; it is followed by `… {percent}%`). The transcript outcome uses the
  genitive noun (`während des Aufteilens`, `während des Transkribierens`, `während des Erkennens
  der Sprechenden`, `während der Verarbeitung`).
- **Capitalization:** sentence case, as the English catalog. Status badges are uppercase
  (`IN WARTESCHLANGE`, `LÄUFT`, `PAUSIERT`, `ABGESCHLOSSEN`, `FEHLGESCHLAGEN`,
  `ABGEBROCHEN`), as the English badges are.
- **Punctuation:** German quotation marks `„…“`; the en dash `–` in the queue lines; the
  ellipsis `…`; no Oxford comma. Colons in error and intent lines follow the English shape
  (`Fehler: {message}`).
- **Access keys:** the `<u>` letter is the editor shortcut, and the app follows it. `S` stays
  (`<u>S</u>peichern`); Discard is `V<u>e</u>rwerfen` (`E`), because `V` is Ctrl+V (paste) in the
  field and `R`, `W`, `F` and `N` belong to the browser. The access key check rejects a shared
  letter, an underline that is not one letter, and the reserved letters A C V X Y Z N Q T W.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `Threads`, and the
  whisper language codes stay untranslated. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** German has `one` and `other`; the ICU messages keep those categories and the
  English placeholders. No dual category is introduced (Arabic's is handled in its own locale).
- **Dates, times, numbers:** formatted by `Intl` from the format locale, never hard-coded in a
  message (`en` → `de-DE`). Durations keep the `h:mm:ss` shape with German digits.
- **Layout:** allow for roughly 30% expansion over English in buttons, headings and aria
  labels; the review page's longest strings are the settings intro and the reassign body.
