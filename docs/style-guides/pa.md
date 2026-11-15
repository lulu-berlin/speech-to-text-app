# Punjabi (pa) — style guide

Companion to `docs/glossary/pa.md` and `docs/reviews/pa.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** the polite imperative (`ਕਰੋ`, `ਚੁਣੋ`, `ਹਟਾਓ`), which is gender-neutral in the
  polite form; running instructions use it throughout, and the actor is dropped where Punjabi
  drops it.
- **Gender:** verbs and adjectives agree with the noun they describe — `ਕਾਰਜ` (m), `ਫਾਈਲ` (f),
  `ਕਤਾਰ` (f) — never with the reader. The status words follow the noun; no sentence addresses the
  reader with a gendered form. The speaker noun `ਸਪੀਕਰ` is a loanword used generically.
- **Punctuation:** the danda `।` ends a sentence; `,` `?` `:` `;` as in the English catalog; the
  ellipsis `…`; the em dash `—`; quotation marks `"…"`. Latin words and placeholders keep a
  half-width space (`CPU`, `{filename} ਅਪਲੋਡ ਹੋ ਰਿਹਾ ਹੈ`).
- **Phases:** nouns after the label (`ਆਡੀਓ ਵੰਡ`, `ਟ੍ਰਾਂਸਕ੍ਰਿਪਸ਼ਨ`, `ਸਪੀਕਰ ਪਛਾਣ`); the outcome line
  is `{phase} ਦੌਰਾਨ {outcome}`, with a position `{time} 'ਤੇ {phase} ਦੌਰਾਨ {outcome}`.
- **Status badges:** `ਕਤਾਰ ਵਿੱਚ`, `ਚੱਲ ਰਿਹਾ ਹੈ`, `ਰੋਕਿਆ ਗਿਆ`, `ਪੂਰਾ ਹੋਇਆ`, `ਅਸਫਲ`, `ਰੱਦ ਕੀਤਾ`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `ਸੰਭਾਲੋ (<u>S</u>)` and
  `ਛੱਡੋ (<u>D</u>)` — because the check requires one underlined letter per button; no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `ਥ੍ਰੈਡ` and the whisper
  language codes; `ਵਰਤੋਂਕਾਰ ਇੰਟਰਫੇਸ`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Punjabi has `one` and `other`; the ICU messages carry both.
- **Dates, times, numbers:** formatted by `Intl` from `pa-IN`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Gurmukhi runs longer than Latin; the settings intro and the reassign body stay the
  longest strings.
