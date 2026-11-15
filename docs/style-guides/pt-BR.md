# Portuguese (Brazil) (pt-BR) — style guide

Companion to `docs/glossary/pt-BR.md` and `docs/reviews/pt-BR.md`; the standard is the user
decision of 2026-11-12 in `docs/specs/localization.md` (gender-neutral and culturally sensitive
wording, terminology compared with established products).

- **Address:** `você` (the Brazilian standard); imperatives are the `você` forms (`verifique`,
  `escolha`, `solte`), which do not carry the reader's gender.
- **Gender:** the speaker noun is `falante`/`falantes`, common-gender (`-e`), never `locutor`
  or `o/a falante`. Prefer neutral phrasings where a sentence would otherwise force a gendered
  adjective or pronoun. The compared products' gendered wordings are recorded in the review
  page as evidence, never adopted when a neutral alternative exists.
- **Phases:** the queue line uses gerunds after the label (`dividindo o áudio… 42%`,
  `transcrevendo… 42%`, `identificando falantes… 42%`). The transcript outcome needs a noun,
  because Portuguese `durante` governs one: its phase strings are `o fatiamento`, `a
transcrição`, `a identificação de falantes`, `o processamento` (`{outcome} durante a
transcrição`). The reviewer of 2026-11-13 caught the earlier gerunds as ungrammatical.
- **Capitalization:** sentence case; the status badges are the catalog's uppercase words
  (`NA FILA`, `EM EXECUÇÃO`, `PAUSADO`, `CONCLUÍDO`, `FALHOU`, `CANCELADO`).
- **Punctuation:** the ellipsis `…`; the em dash `—` as the English catalog; quotation marks
  `“…”`; no Oxford comma. Colons in error lines follow the English shape (`Erro: {message}`).
- **Access keys:** Save is `<u>S</u>alvar`, Discard `<u>D</u>escartar`; the access-key check
  rejects a shared letter, an underline that is not one letter, and the reserved letters A C V
  X Y Z N Q T W.
- **Products and codes:** `Speech-to-Text`, file formats, `threads`, `CPU`, `GPU` and the whisper
  language codes stay untranslated. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Portuguese has `one`, `many` and `other`; the ICU messages carry all three.
- **Dates, times, numbers:** formatted by `Intl` from `pt-BR`, never hard-coded in a message.
  Durations keep the `h:mm:ss` shape with Brazilian digits.
- **Layout:** allow for roughly 30% expansion over English; the review page's longest strings
  are the settings intro and the reassign body.
