# Italian (it) — style guide

Companion to `docs/glossary/it.md` and `docs/reviews/it.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md` (gender-neutral and culturally sensitive wording,
terminology compared with established products).

- **Address:** the informal second person (`tu`), as the compared Italian consumer UIs write:
  `Controlla l'indirizzo`, `Trascina qui i file audio`, `Scegli`. Buttons are imperatives
  (`Salva`, `Annulla`, `Elimina`, `Ripristina`), which carry no reader gender.
- **Gender:** the speaker noun is `voce`/`voci` (feminine, the object the app handles), never the
  generic masculine `parlante`/`parlanti` or `locutore`. Status adjectives agree with the noun
  `attività`, never with a person: `COMPLETATA`, `FALLITA`, `ANNULLATA`. Participles agree with
  the item they describe (a file, a task, a voice), not with a reader. No `·` or `*` inclusive
  forms.
- **Phases:** nouns after the label (`Suddivisione`, `Trascrizione`, `Identificazione delle voci`).
  In the transcript outcome the phase string carries its own article — `{outcome} durante
{phase}` with `la suddivisione`, `la trascrizione`, `l'identificazione delle voci`,
  `l'elaborazione` — as French and the Spanish catalogs do, because a template cannot elide
  (`durante l'identificazione`, never `durante la identificazione`).
- **Capitalisation:** sentence case; the status badges are the catalog's uppercase words
  (`IN CODA`, `IN ESECUZIONE`, `IN PAUSA`, `COMPLETATA`, `FALLITA`, `ANNULLATA`).
- **Punctuation:** the ellipsis `…`; the em dash `—` as the English catalog; straight apostrophe
  `'` (`l'attività`, `un'altra`); quotation marks `"…"` as the English catalog. No space before
  `?`, `!` or `%` (unlike French).
- **Page names:** capitalised as standalone labels and buttons (`Cronologia`, `Torna alla
Cronologia`), lowercase inside a running sentence (`vai alla coda`, `vedi la cronologia`), as
  Italian product UIs write them.
- **Access keys:** Save is `<u>S</u>alva`, Discard `<u>I</u>gnora`; the access-key check rejects a
  shared letter, an underline that is not one letter, and the reserved letters A C V X Y Z N Q T W.
- **Products and codes:** `Speech-to-Text`, file formats, `thread`, `CPU`, `GPU` and the whisper
  language codes stay untranslated; `interfaccia utente`, never `frontend`. The UI locale's own
  names come from `docs/api/ui-locales.json`.
- **Plurals:** Italian has `one`, `many` and `other`; the ICU messages carry all three, and the
  `many` branch writes `# di …` (Italian takes `di` from a million up).
- **Dates, times, numbers:** formatted by `Intl` from `it-IT`, never hard-coded in a message.
- **Layout:** allow for roughly 25% expansion over English; the settings intro and the reassign
  body stay the longest strings.
