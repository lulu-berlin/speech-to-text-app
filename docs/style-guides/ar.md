# Arabic — style guide

Companion to `docs/glossary/ar.md` and `docs/reviews/ar.md`; the standard and policy are the
user decisions of 2026-11-12 in `docs/specs/localization.md`.

- **Language:** Modern Standard Arabic, as the compared products use; no dialect forms.
- **Address:** nominal/masdar style (`التحقق…`, `إلغاء`, `الإفلات…`) rather than the masculine
  imperative; the reader's gender is never assumed. The products' masculine imperatives are
  evidence only.
- **Gender:** no generic masculine. Speaker terms are `الأصوات` (voices), a turn is
  `المداخلة`, and the buttons use masdars (`فتح`, `حذف`, `إعادة المحاولة`). `المتحدث` from
  the compared products is evidence only, never used.
- **Phases:** the queue line uses the bare noun (`النسخ… ٤٢٪`); the transcript outcome uses the
  noun with its article (`فشل أثناء النسخ`, `أُلغي أثناء التقسيم`).
- **Typography:** right-to-left; `؟` for questions, `،` and `؛` for punctuation, `٪` for the
  percent sign, `«»` for quotes, the ellipsis `…`, the em dash `—` in the queue lines. Latin
  placeholders (filenames, CPU, GPU) are isolated from the Arabic by the bidi algorithm where
  the layout provides it; the RTL item owns the layout mirroring.
- **Capitalization:** Arabic has no case; the status badges are the plain Arabic words
  (`في الانتظار`, `قيد التشغيل`, `متوقفة مؤقتًا`, `مكتملة`, `فشلت`, `أُلغيت`).
- **Access keys:** the `<u>` letter is the editor shortcut: `حفظ` → `ح`, `تجاهل` → `ت` (the
  Arabic keyboard). The access key check rejects a shared letter, a multi-letter underline and
  the reserved Latin A C V X Y Z N Q T W; Arabic letters are distinct.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `الخيوط` for threads,
  and the whisper language codes stay untranslated; `upload` is `الرفع`.
- **Plurals:** Arabic has `zero`, `one`, `two` (the dual), `few`, `many` and `other`; the
  parity check enforces all six (`# مقاطع`, `# مقطعًا`, `# مقطع`, `مقطعان`).
- **Dates, times, numbers:** formatted by `Intl` from the format locale (`ar-EG`), in
  Arabic-Indic digits; never in a message. The queue percent is already formatted this way.
- **Layout:** right-to-left mirroring is the RTL item; allow for Arabic's shorter strings but
  keep the Latin product names and filenames legible inside them.
