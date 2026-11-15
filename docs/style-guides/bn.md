# Bengali (bn) — style guide

Companion to `docs/glossary/bn.md` and `docs/reviews/bn.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** the polite imperative (`করুন`) throughout (`বেছে নিন`, `মুছুন`, `দেখুন`). Bengali
  verbs agree with person and honorific level, never with the addressee's gender, so instructions
  are neutral by form.
- **Gender:** Bengali has no grammatical gender agreement in the verb; nouns and adjectives carry
  no reader gender. `স্পিকার` is an ungendered loanword: it names the person who speaks and never
  the reader; no sentence addresses the reader with a gendered title or form.
- **Punctuation:** the danda `।` ends a sentence; `,` `?` `:` `;` as in the English catalog; the
  ellipsis `…`; the em dash `—`; quotation marks `"…"`. Latin words keep a half-width space in
  running text (`Speech-to-Text হোম`, `CPU`, `{filename} আপলোড হচ্ছে`), while a Bengali case
  suffix attaches to a placeholder with a hyphen and no space (`{filename}-এর`, `{number}-এর`,
  `CPU-তে`).
- **Phases:** nouns after the label (`অডিও বিভাজন`, `প্রতিলিপিকরণ`, `স্পিকার শনাক্তকরণ`); the
  outcome line is `{phase} চলাকালীন {outcome}`, with a position `{time}-এ {phase} চলাকালীন
{outcome}`.
- **Status badges:** `সারিতে`, `চলছে`, `বিরত`, `সম্পন্ন`, `ব্যর্থ`, `বাতিল`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `সংরক্ষণ (S)` and
  `বাতিল (D)` — because the dialog's shortcut check requires one underlined letter per button; no
  two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `থ্রেড` and the whisper
  language codes; `ব্যবহারকারী ইন্টারফেস`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Bengali has `one` and `other`; the ICU messages carry both.
- **Dates, times, numbers:** formatted by `Intl` from `bn-BD`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Bengali runs longer than Latin for the same content; the longest strings are the
  settings intro and the reassign body.
