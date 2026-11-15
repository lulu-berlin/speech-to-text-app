# Chinese (Traditional) (zh-Hant) — style guide

Companion to `docs/glossary/zh-Hant.md` and `docs/reviews/zh-Hant.md`; the standard is the user
decision of 2026-11-12 in `docs/specs/localization.md`. The script conventions are Taiwan's.

- **Address:** no pronoun. Chinese imperatives carry no reader gender, so instructions read
  directly (`請檢查網址`, `將音訊檔案拖曳到此處`), and the actor is dropped where the English drops it.
- **Gender:** Chinese has no grammatical gender. The speaker noun is `說話者`, the voice's source,
  not the person; no `他`/`她` pronoun appears (the character occurs only inside `其他`), a task or
  segment is `它` or an omitted subject.
- **Punctuation:** full-width `，` `。` `？` `：` in Chinese text; the dash is `——`; the ellipsis
  `…`; the quotation marks `「…」` for a quoted control and `“…”` as the English catalog. Half-width
  spaces separate Latin words from Chinese (`Speech-to-Text 首頁`, `CPU 無法使用`, `{filename} 已轉錄`),
  and no space sits beside Chinese punctuation.
- **Phases:** nouns after the label (`音訊切分`, `轉錄`, `說話者分離`); the outcome line is
  `{phase}時{outcome}` (`轉錄時失敗`), with a position `在 {time} 處{phase}時{outcome}`
  (`在 0:15 處轉錄時失敗`).
- **Status badges:** `排隊中`, `執行中`, `已暫停`, `已完成`, `失敗`, `已取消`.
- **Access keys:** the editor's buttons show the shortcut in full-width parentheses —
  `儲存（S）` and `捨棄（D）` — because the dialog's shortcut check requires one underlined letter
  per button; no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `執行緒` and the whisper
  language codes; `使用者介面`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Chinese has only the `other` ICU category, and the messages carry just it.
- **Dates, times, numbers:** formatted by `Intl` from `zh-Hant-TW`, never hard-coded; the percent
  sign of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** the longest strings are `transcript.speakers.mergeBody` (51 characters),
  `settings.intro` (50), `queue.fallback.detail` and `transcript.reassign.body` (42 each); by
  rendered width `settings.intro` is the widest, and Latin placeholders need their surrounding
  spaces.
