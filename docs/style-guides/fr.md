# French (fr) — style guide

Companion to `docs/glossary/fr.md` and `docs/reviews/fr.md`; the standard and the policy are the
user decisions of 2026-11-12 in `docs/specs/localization.md`.

- **Address:** `vous` with the imperative (`Vérifiez l’adresse`, `Déposez des fichiers`), as the
  compared French UIs do.
- **Gender:** no generic masculine and no inclusive point (`·`) forms. Speaker terms are epicene:
  `voix` (`Voix`, `Nombre de voix`, `Identification des voix`, `Renommer la voix 1`) — the app
  handles the speakers' voices, not the speakers as individuals — and `prise de parole` for a
  turn. The comparison is for idiomatic wording only: a
  non-gender-neutral or culturally insensitive term is never accepted, even when a compared
  product uses it. Participles agree with the file or task, never with a person.
- **Phases:** the queue line uses the bare noun (`Transcription… 42 %`); the transcript
  outcome uses a noun with its article (`Échec pendant la transcription`,
  `Annulation pendant le découpage`).
- **Typography:** narrow no-break space (U+202F) before `?`, `;` and `%`; no-break space (U+00A0)
  before `:` and inside `« »`; typographic apostrophe `’`; ellipsis `…`; em dash `—` in the queue
  lines, as the English.
- **Capitalization:** sentence case. Status badges are uppercase (`EN ATTENTE`, `EN COURS`,
  `EN PAUSE`, `TERMINÉ`, `ÉCHEC`, `ANNULÉ`).
- **Access keys:** the `<u>` letter is the editor shortcut, and the app follows it:
  `Enregistrer` → `E`, `Ignorer` → `I` (English `S` and `D`). The access key check rejects a
  shared letter, an underline that is not one letter, and the reserved A C V X Y Z N Q T W.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `threads` and the whisper
  language codes stay untranslated; `upload` is `importer`, not `téléverser`.
- **Plurals:** French has `one`, `many` and `other` (CLDR); `many` (millions) takes `de`:
  `# de segments sélectionnés`. The parity check enforces the categories.
- **Dates, times, numbers:** formatted by `Intl` from the format locale (`fr` → `fr-FR`), never
  in a message.
- **Layout:** allow roughly 30% expansion; the longest strings are the settings intro, the
  diarizing phase and the reassign body.
