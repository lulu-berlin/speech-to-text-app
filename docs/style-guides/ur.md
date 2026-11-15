# Urdu (ur) — style guide

Companion to `docs/glossary/ur.md` and `docs/reviews/ur.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Direction:** the document runs right to left (`direction: rtl` in `docs/api/ui-locales.json`);
  Latin placeholders, `CPU`, `GPU` and file names keep their own direction inside the RTL text, and
  the layout mirrors the interface, which the direction item covers.
- **Address:** buttons and actions use the plain imperative (`منسوخ کریں`, `حذف کریں`, `روکیں`),
  and running instructions use the same form (`پتہ جانچیں`, `منتخب کریں`). Urdu imperative forms
  carry no grammatical gender, and no pronoun or title addresses the reader.
- **Gender:** `اسپیکر` is used generically for the voice's source and `باری` for what is said;
  no sentence marks a gender for either.
- **Punctuation:** `؟` closes a question, and `،` and `؛` stand inside a sentence, as the Arabic
  catalog uses them; `.` closes a sentence, and `:` follows the English catalog; the ellipsis
  `…`; the em dash `—`; quotation marks `"…"`. Labels keep the English form without a final stop
  (`یہ فائل فارمیٹ تعاون یافتہ نہیں ہے`, `نہیں ملا`).
- **Phases:** nouns after the label (`آڈیو تقسیم`, `ٹرانسکرپشن`, `اسپیکر کی شناخت`); the outcome
  line is `{phase} کے دوران {outcome}`, with a position `{time} پر {phase} کے دوران {outcome}`.
- **Status badges:** `قطار میں`, `چل رہا ہے`, `موقوف`, `مکمل`, `ناکام`, `منسوخ`; the history
  filter reuses `مکمل`, `ناکام` and `منسوخ`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `محفوظ کریں (<u>S</u>)`
  and `نظر انداز کریں (<u>D</u>)` — because the check requires one underlined letter per button;
  no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `تھریڈز` and the whisper
  language codes; `صارف انٹرفیس`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json` (`اردو` for this one).
- **Plurals:** Urdu has the `one` and `other` ICU categories, and both messages are written; `#`
  stays where the number belongs.
- **Dates, times, numbers:** formatted by `Intl` from `ur-PK`, never hard-coded; the percent sign of
  `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Urdu is close to English in length: `settings.intro` is 142 code points against 136,
  then `queue.fallback.detail` (130 against 114) and `transcript.reassign.body` (123 against 120).
