# Japanese (ja) — style guide

Companion to `docs/glossary/ja.md` and `docs/reviews/ja.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** the polite form (`です・ます`) and the request form `〜してください`; the actor is
  omitted where Japanese omits it. The language has no grammatical gender and no gendered
  agreement, so instructions are neutral by form.
- **Gender:** Japanese has no grammatical gender; the speaker noun `話者` names the voice's source
  and `ユーザー` is genderless. No sentence addresses the reader with a gendered title or pronoun.
- **Punctuation:** `。` and `、` in Japanese text, with no space beside them; the question mark is
  the full-width `？` with no space after it; the ellipsis `…`; the em dash `—` set tight (no
  spaces) in Japanese text; quotation marks `「…」` for a quoted control. Latin words and
  placeholders keep a half-width space where needed (`CPU`, `{filename} をアップロード中`).
- **Sentences:** body sentences end with `。`; labels, buttons, status lines and the queue's
  finished lines follow the English string's terminal punctuation.
- **Phases:** nouns after the label (`音声の分割`, `文字起こし`, `話者分離`); the outcome line is
  `{phase}中に{outcome}。`, with a position `{time} の時点で{phase}中に{outcome}。`.
- **Status badges:** `待機中`, `実行中`, `一時停止中`, `完了`, `失敗`, `キャンセル済み`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `保存 (S)` and
  `破棄 (D)` — because the shortcut check requires one underlined letter per button; no two share
  one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `スレッド` and the whisper
  language codes; `ユーザー インターフェイス`, never `frontend`. The UI locale's own names come
  from `docs/api/ui-locales.json`.
- **Plurals:** Japanese has only the `other` ICU category, and the message carries just it.
- **Dates, times, numbers:** formatted by `Intl` from `ja-JP`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Japanese is shorter than English in characters but the settings intro and the
  reassign body stay the longest strings.
