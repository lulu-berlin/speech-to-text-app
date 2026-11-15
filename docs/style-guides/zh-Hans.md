# Chinese (Simplified) (zh-Hans) — style guide

Companion to `docs/glossary/zh-Hans.md` and `docs/reviews/zh-Hans.md`; the standard is the user
decision of 2026-11-12 in `docs/specs/localization.md`.

- **Address:** no pronoun. Chinese imperatives carry no reader gender, so instructions read
  directly (`请检查地址`, `将音频文件拖到此处`), and the actor is dropped where the English drops it.
- **Gender:** Chinese has no grammatical gender. The speaker noun is `说话人`, the voice's source,
  not the person; no `他`/`她` pronoun appears (the character occurs only inside `其他`), a task or
  segment is `它` or an omitted subject, and no sentence addresses the reader with a gendered
  form.
- **Punctuation:** full-width `，` `。` `？` `：` in Chinese text; the dash is `——`; the ellipsis
  `…`; the quotation marks `“…”`. Half-width spaces separate Latin words from Chinese
  (`Speech-to-Text 首页`, `CPU 不可用`, `{filename} 已转写`), and no space sits beside Chinese
  punctuation.
- **Phases:** nouns after the label (`音频切分`, `转写`, `说话人分离`); the outcome line is
  `{phase}时{outcome}` (`转写时失败`) and with a position `在 {time} 处{phase}时{outcome}`
  (`在 0:15 处转写时失败`).
- **Status badges:** `排队中`, `运行中`, `已暂停`, `已完成`, `失败`, `已取消` (no case in Chinese).
- **Access keys:** the editor's buttons show the shortcut in full-width parentheses —
  `保存（S）` and `放弃（D）` — because the dialog's shortcut check requires one underlined letter
  per button; no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `线程` and the whisper
  language codes; `用户界面`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Chinese has only the `other` ICU category, and the messages carry just it.
- **Dates, times, numbers:** formatted by `Intl` from `zh-Hans-CN`, never hard-coded; the percent
  sign of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Chinese usually runs shorter than English, but not everywhere; the longest strings
  are `transcript.speakers.mergeBody` (51 characters), `settings.intro` (50),
  `queue.fallback.detail` and `transcript.reassign.body` (42 each), and Latin placeholders need
  their surrounding spaces.
