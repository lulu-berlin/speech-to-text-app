# Telugu (te) — style guide

Companion to `docs/glossary/te.md` and `docs/reviews/te.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** buttons and actions use the respectful plural imperative (`రద్దు చేయండి`,
  `తొలగించండి`, `ఎంచుకోండి`), and running instructions use the same form (`చిరునామాను తనిఖీ
చేయండి`). It is the form Apple's Telugu pages use and it marks no gender, so no sentence has to
  state the reader's gender.
- **Gender:** no title or pronoun addresses the reader; `వక్త` / `వక్తలు` is common gender and names
  the voice's source, and `వారి` in the merge body is the neutral plural, never `అతని`/`ఆమె`.
  Sentences about `టాస్క్` agree in the neuter (`ఈ టాస్క్ ఉనికిలో లేదు`).
- **Punctuation:** sentences end with `.`; ASCII `,` `:` `?` as the English catalog; the ellipsis
  `…`; the em dash `—`; quotation marks `"…"`. Labels and titles keep the English form without a
  final stop (`ఈ ఫైల్ ఫార్మాట్కు మద్దతు లేదు`, `కనుగొనబడలేదు`).
- **Phases:** nouns after the label (`ఆడియో విభజన`, `ట్రాన్స్క్రిప్షన్`, `వక్తల గుర్తింపు`); the
  outcome line is `{phase} సమయంలో {outcome}`, with a position `{time} వద్ద {phase} సమయంలో {outcome}`.
- **Status badges:** `క్యూలో ఉంది`, `నడుస్తోంది`, `పాజ్ చేయబడింది`, `పూర్తయింది`, `విఫలమైంది`,
  `రద్దు చేయబడింది`; the history filter reuses `పూర్తయింది`, `విఫలమైంది` and `రద్దు చేయబడింది`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `సేవ్ చేయండి (<u>S</u>)`
  and `విస్మరించండి (<u>D</u>)` — because the check requires one underlined letter per button; no
  two share one.
- **Products and codes:** `స్పీచ్-టు-టెక్స్ట్`, file formats, `CPU`, `GPU`, `థ్రెడ్లు` and the
  whisper language codes; `యూజర్ ఇంటర్ఫేస్`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Telugu has the `one` and `other` ICU categories, and both messages are written;
  `#` stays where the number belongs.
- **Dates, times, numbers:** formatted by `Intl` from `te-IN`, never hard-coded; the percent sign of
  `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Telugu runs longer than English in the longest strings: `settings.intro` (175 code
  points against 136) is the longest, then `transcript.reassign.body` (138 against 120) and
  `queue.fallback.detail` (126 against 114).
