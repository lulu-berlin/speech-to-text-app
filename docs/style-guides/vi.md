# Vietnamese (vi) — style guide

Companion to `docs/glossary/vi.md` and `docs/reviews/vi.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** buttons and actions are verb phrases (`Lưu`, `Xóa`, `Hủy`), and running
  instructions use the neutral `hãy`/imperative form (`Kiểm tra địa chỉ`, `thả tệp vào đây`).
  Vietnamese has no grammatical gender, so no sentence can address the reader with one.
- **Gender:** none to mark; the speaker noun `người nói` names the voice's source, never the
  reader.
- **Punctuation:** ASCII `.` `,` `?` `:` as the English catalog; the ellipsis `…`; the em dash
  `—`; quotation marks `"…"`. Half-width spaces separate Latin words and placeholders
  (`CPU`, `Đang tải lên {filename}`).
- **Phases:** nouns after the label (`Chia âm thanh`, `Chép lời`, `Nhận diện người nói`); the
  outcome line is `{outcome} khi đang {phase}`, with a position `{outcome} khi đang {phase} ở mốc {time}`.
- **Status badges:** `ĐANG CHỜ`, `ĐANG CHẠY`, `ĐÃ TẠM DỪNG`, `ĐÃ HOÀN TẤT`, `THẤT BẠI`, `ĐÃ HỦY`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `Lưu (<u>L</u>)` and
  `Bỏ (<u>B</u>)` — because the check requires one underlined letter per button; no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `luồng` and the whisper
  language codes; `giao diện người dùng`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Vietnamese has only the `other` ICU category, and the message carries just it.
- **Dates, times, numbers:** formatted by `Intl` from `vi-VN`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Vietnamese runs longer than English; the settings intro and the reassign body stay
  the longest strings.
