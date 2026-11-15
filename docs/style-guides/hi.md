# Hindi (hi) — style guide

Companion to `docs/glossary/hi.md` and `docs/reviews/hi.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** the polite imperative (`करें`) throughout, including instructions (`जाँचें`,
  `चुनें`, `छोड़ें`). Hindi imperatives carry no addressee gender, so they are neutral by form.
- **Gender:** Hindi verbs and adjectives agree with the noun they describe — `कार्य` (m),
  `फ़ाइल` (f), `कतार` (f), `त्रुटि` (f) — never with the reader. Status words agree with
  `कार्य`: `चल रहा है`, `रोका गया`, `पूर्ण`, `विफल`, `रद्द`. The speaker noun `वक्ता` is
  grammatically masculine but used generically for any speaker; no sentence addresses the reader
  with a gendered form or a gendered title.
- **Punctuation:** the danda `।` ends a sentence; `,` `?` `:` `;` as in the English catalog;
  the ellipsis `…`; the em dash `—`; quotation marks `"…"`. Half-width spaces separate Latin words
  from Devanagari (`Speech-to-Text होम`, `CPU {index}`, `{filename} का अपलोड हो रहा है`).
- **Phases:** nouns after the label (`ऑडियो विभाजन`, `प्रतिलेखन`, `वक्ता पहचान`); the outcome
  line is `{phase} के दौरान {outcome}`, with a position `{time} पर {phase} के दौरान {outcome}`.
- **Status badges:** `कतार में`, `चल रहा है`, `रोका गया`, `पूर्ण`, `विफल`, `रद्द`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `सहेजें (S)` and
  `छोड़ें (D)` — because the dialog's shortcut check requires one underlined letter per button;
  no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `थ्रेड` and the whisper
  language codes; `उपयोगकर्ता इंटरफ़ेस`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Hindi has `one` and `other`; the ICU messages carry both.
- **Dates, times, numbers:** formatted by `Intl` from `hi-IN`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Devanagari runs longer than Latin; the longest strings are the settings intro and
  the reassign body, and the merge confirmation carries two inserted names.
