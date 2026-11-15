# Russian (ru) — style guide

Companion to `docs/glossary/ru.md` and `docs/reviews/ru.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** buttons are infinitives (`Сохранить`, `Удалить`), which carry no gender; the cancel
  button is the established noun `Отмена`, which carries no gender either; running instructions use
  the plural imperative (`Проверьте`, `Выберите`, `Перетащите`, `Отпустите`), which carries no
  gender either. No sentence addresses the reader with a gendered form: no past-tense verb or
  short adjective agreeing with the reader.
- **Gender:** verbs and adjectives agree with the noun they describe — `задача` (f), `файл` (m),
  `очередь` (f) — never with the reader. The status badges agree with `задача`: `ВЫПОЛНЯЕТСЯ`,
  `ПРИОСТАНОВЛЕНА`, `ЗАВЕРШЕНА`, `ОТМЕНЕНА`; `ОШИБКА` is a noun. The speaker noun `спикер` is a
  loanword, masculine in form and used generically.
- **Punctuation:** the ellipsis `…`; the em dash `—`; quotation marks `«…»` when a control's own
  name is quoted, `"…"` as the English catalog elsewhere; no space before `?`, `!`, `%`; a
  non-breaking space before `—` where it separates clauses is not required.
- **Phases:** nouns after the label (`Разбиение аудио`, `Транскрипция`, `Определение спикеров`);
  the transcript outcome carries the genitive, because the template reads `{outcome} во время
{phase}` (`транскрипции`, `определения спикеров`, `обработки`), and with a position
  `{outcome} во время {phase} на отметке {time}`.
- **Status badges:** `В ОЧЕРЕДИ`, `ВЫПОЛНЯЕТСЯ`, `ПРИОСТАНОВЛЕНА`, `ЗАВЕРШЕНА`, `ОШИБКА`,
  `ОТМЕНЕНА`.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `Сохранить (S)`,
  `Не сохранять (D)` — because the check requires one underlined letter per button; no two share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `потоки` and the whisper
  language codes; `интерфейс`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Russian has `one`, `few`, `many` and `other`; the ICU message carries all four.
- **Dates, times, numbers:** formatted by `Intl` from `ru-RU`, never hard-coded; the percent sign
  of `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Russian runs longer than English; the longest strings are the settings intro and the
  reassign body.
