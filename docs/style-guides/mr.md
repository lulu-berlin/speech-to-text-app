# Marathi (mr) — style guide

Companion to `docs/glossary/mr.md` and `docs/reviews/mr.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** buttons and actions use the `-ा` imperative (`रद्द करा`, `हटवा`, `निवडा`), and
  running instructions use the same plain imperative (`पत्ता तपासा`, `ऑडिओ फाइल इथे टाका`).
  Marathi imperatives carry no grammatical gender, so no sentence has to mark the reader's.
- **Gender:** no title or pronoun addresses the reader; `वक्ता` / `वक्ते` is common gender and
  names the voice's source. Sentences about the task (`कार्य`, neuter) agree in the neuter
  (`हे कार्य अस्तित्वात नाही`), never in the masculine default.
- **Punctuation:** sentences end with `.`, as Apple's Marathi articles do, not with the Devanagari danda;
  labels, titles and fragments keep the English form without a final stop (`फाइल स्वरूप समर्थित
नाही`, `पृष्ठ सापडले नाही`); ASCII `,` `:` `?` as the English catalog; the ellipsis `…`; the em dash `—`;
  quotation marks `"…"`.
- **Phases:** nouns after the label (`ऑडिओ विभाजन`, `प्रतिलेखन`, `वक्ता ओळख`); the outcome line
  is `{phase} दरम्यान {outcome}`, with a position `{time} ला {phase} दरम्यान {outcome}`.
- **Status badges:** `रांगेत`, `सुरू आहे`, `थांबवले`, `पूर्ण`, `अयशस्वी`, `रद्द केले`; the
  history filter reuses `पूर्ण`, `अयशस्वी` and `रद्द केले`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `जतन करा (<u>S</u>)` and
  `टाकून द्या (<u>D</u>)` — because the check requires one underlined letter per button; no two
  share one.
- **Products and codes:** `स्पीच-टू-टेक्स्ट`, file formats, `CPU`, `GPU`, `थ्रेड्स` and the whisper
  language codes; `वापरकर्ता इंटरफेस`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Marathi has the `one` and `other` ICU categories, and both messages are written;
  `#` stays where the number belongs.
- **Dates, times, numbers:** formatted by `Intl` from `mr-IN`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Marathi runs longer than English in most strings: `settings.intro` (143 code points against 136) is the longest, then `transcript.reassign.body` (134 against 120);
  `queue.fallback.detail` is 113 against the English 114, so length is not the only guide.
