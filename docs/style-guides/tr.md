# Turkish (tr) — style guide

Companion to `docs/glossary/tr.md` and `docs/reviews/tr.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** buttons are imperatives (`Kaydet`, `Sil`) and `İptal` is a noun; neither form carries gender; running
  instructions use the polite second person (`... gidin`), which also carries no gender. Turkish
  has no grammatical gender at all, so no sentence can address the reader with one.
- **Gender:** none to mark; the speaker noun `konuşmacı` names the voice's source and never the
  reader. No gendered title or pronoun exists in the catalog.
- **Punctuation:** the ASCII period and comma as in the English catalog; the ellipsis `…`; the em
  dash `—`; quotation marks `"…"` as the English catalog. Half-width spaces between Latin words and placeholders
  (`CPU`, `{filename} yükleniyor`); no space follows a placeholder before a comma.
- **Phases:** nouns after the label (`Ses bölme`, `Transkripsiyon`, `Konuşmacı tanıma`); the outcome
  line is `{phase} sırasında {outcome}`, with a position `{time} konumunda {phase} sırasında {outcome}`.
- **Status badges:** `SIRADA`, `ÇALIŞIYOR`, `DURAKLATILDI`, `TAMAMLANDI`, `BAŞARISIZ`, `İPTAL EDİLDİ`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `Kaydet (<u>K</u>)` and
  `Bırak (<u>B</u>)` — because the check requires one underlined letter per button; no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `iş parçacığı` and the
  whisper language codes; `kullanıcı arayüzü`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Turkish has `one` and `other`; the ICU messages carry both.
- **Dates, times, numbers:** formatted by `Intl` from `tr-TR`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Turkish runs longer than English; the settings intro and the reassign body stay the
  longest strings.
