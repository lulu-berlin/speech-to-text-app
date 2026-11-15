# Tamil (ta) — style guide

Companion to `docs/glossary/ta.md` and `docs/reviews/ta.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** control labels use the plain singular imperative (`ரத்து செய்`, `நீக்கு`,
  `தேர்ந்தெடு`) — gender-neutral, and not the `-ங்கள்` honorific — while text actions and running
  instructions use the `-வும்` form (`மீண்டும் முயற்சிக்கவும்`, `முகவரியைச் சரிபார்க்கவும்`), the
  pattern Apple's Tamil pages use. Tamil imperative forms carry no grammatical gender, and no
  pronoun or title addresses the reader.
- **Gender:** `பேச்சாளர்` / `பேச்சாளர்கள்` is common gender and names the voice's source; the merge
  body uses `அவர்களின்`, the neutral plural, never `அவரது`/`அவள்`.
- **Punctuation:** a sentence the English closes with a stop closes with `.`; labels, titles and
  link-final body strings keep the English form without a final stop (`இந்தக் கோப்பு வடிவம்
ஆதரிக்கப்படவில்லை`, `பக்கம் கிடைக்கவில்லை`, `… <link>வரிசைக்குச் செல்</link>`); ASCII `,` `:`
  `?` as the English catalog; the ellipsis `…`; the em dash `—`; quotation marks `"…"`.
- **Phases:** nouns after the label (`ஆடியோ பிரிப்பு`, `டிரான்ஸ்கிரிப்ஷன்`, `பேச்சாளர் கண்டறிதல்`);
  the outcome line is `{phase} நிலையில் {outcome}`, with a position
  `{time} இல் {phase} நிலையில் {outcome}`.
- **Status badges:** `வரிசையில்`, `இயங்குகிறது`, `இடைநிறுத்தப்பட்டது`, `முடிந்தது`, `தோல்வி`,
  `ரத்து செய்யப்பட்டது`; the history filter reuses `முடிந்தது`, `தோல்வி` and `ரத்து செய்யப்பட்டது`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `சேமி (<u>S</u>)` and
  `நிராகரி (<u>D</u>)` — because the check requires one underlined letter per button; no two share
  one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `த்ரெட்கள்` and the whisper
  language codes; `பயனர் இடைமுகம்`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Tamil has the `one` and `other` ICU categories, and both messages are written; `#`
  stays where the number belongs.
- **Dates, times, numbers:** formatted by `Intl` from `ta-IN`, never hard-coded; the percent sign of
  `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Tamil runs longer than English in the longest strings: `settings.intro` is 183 code
  points against 136, then `queue.fallback.detail` (149 against 114) and `transcript.reassign.body`
  (146 against 120).
