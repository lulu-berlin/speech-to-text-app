# Korean (ko) — style guide

Companion to `docs/glossary/ko.md` and `docs/reviews/ko.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** buttons and actions are noun or stem labels (`저장`, `취소`, `삭제`, `재생`), and
  running instructions use the polite `하세요` form (`주소를 확인하거나 대기열로 이동하세요`), the
  pattern Google's Korean UI uses. Korean marks no gender in either form, and no pronoun or title
  addresses the reader.
- **Particles:** a placeholder that receives a particle carries both forms, because a speaker name
  or file name is unknown: `{names}을(를)`, `{survivor}(으)로`, and a file name that is the object
  of a title's verb takes `을(를)` (`{filename}을(를) 취소할까요?`).
- **Punctuation:** ASCII `.` `,` `:` `?` as the English catalog; the ellipsis `…`; the em dash
  `—`; quotation marks `"…"`. Labels keep the English form without a final stop (`지원되지 않는
파일 형식`, `찾을 수 없음`).
- **Phases:** nouns after the label (`오디오 분할`, `스크립트 작성`, `화자 분리`); the outcome line
  is `{phase} 중 {outcome}`, with a position `{time}에서 {phase} 중 {outcome}`.
- **Status badges:** `대기 중`, `실행 중`, `일시중지됨`, `완료됨`, `실패`, `취소됨`; the history
  filter reuses `완료됨`, `실패` and `취소됨`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `저장 (<u>S</u>)` and
  `버리기 (<u>D</u>)` — because the check requires one underlined letter per button; no two share
  one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `스레드` and the whisper
  language codes; `사용자 인터페이스`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Korean has only the `other` ICU category, and the message carries just it.
- **Dates, times, numbers:** formatted by `Intl` from `ko-KR`, never hard-coded; the percent sign of
  `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Korean runs shorter than English: `settings.intro` is 85 code points against 136, and
  `transcript.reassign.body` 81 against 120; the longest string is still `settings.intro`.
