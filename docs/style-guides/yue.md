# Cantonese (yue) — style guide

Companion to `docs/glossary/yue.md` and `docs/reviews/yue.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Register:** interface copy is standard written Chinese in Hong Kong usage, not colloquial
  Cantonese: the spoken particles `嘅`, `咗`, `喺`, `唔` and the particle-final `啦`/`㗎` never
  appear, because a product's own Hong Kong UI writes the same way. The locale's native name is
  `粵語`; the whisper code it maps to is `yue`.
- **Vocabulary:** Hong Kong terms throughout — `上載` (not `上傳`), `檔案` (not `文件`), `執行緒`,
  `裝置`, `使用者介面`, `預設`. `講者` names the voice's source; `轉錄稿` is the result and `轉錄`
  the process.
- **Address:** buttons and actions are verb phrases (`儲存`, `刪除`, `取消`), and running
  instructions use the plain imperative (`請檢查網址`, `將音訊檔案拖到此處`). No pronoun addresses
  the reader; Chinese has no grammatical gender, so none can be marked.
- **Punctuation:** full-width `，` `。` `：` `？` as the sibling Chinese catalogs; the ideographic
  space is not used; the full-width parentheses `（…）` around shortcut and format placeholders; the
  em dash `——` in prose, while `device.gpuNamed` keeps the spaced half-width `—` of the English
  catalog; quotation marks `「…」`.
- **Phases:** nouns after the label (`分割音訊`, `轉錄`, `講者識別`); the outcome line is
  `{phase}時{outcome}`, with a position `在 {time} 處{phase}時{outcome}`, as in the Traditional
  Chinese catalog.
- **Status badges:** `排隊中`, `執行中`, `已暫停`, `已完成`, `失敗`, `已取消`; the history filter
  reuses only `已完成`, `失敗` and `已取消`, the other three are queue and transcript states.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `儲存（<u>S</u>）` and
  `捨棄（<u>D</u>）` — because the check requires one underlined letter per button; no two share one.
- **Products and codes:** `語音轉文字`, file formats, `CPU`, `GPU`, `執行緒` and the whisper
  language codes; `使用者介面`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json` (`粵語` for this one).
- **Plurals:** Cantonese has only the `other` ICU category, and the message carries just it.
- **Dates, times, numbers:** formatted by `Intl` from `yue-HK`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** written Chinese runs short; `transcript.speakers.mergeBody` (54 characters) is the
  longest string, then `settings.intro` (49) and `queue.fallback.detail` (45).
