# Portuguese (Portugal) (pt-PT) — style guide

Companion to `docs/glossary/pt-PT.md` and `docs/reviews/pt-PT.md`. The shared rules are in
`docs/style-guides/pt-BR.md` (standard: user decision of 2026-11-12 in `docs/specs/localization.md`);
only the European Portuguese differences are recorded here.

- **Progress:** European Portuguese continues with `a` + infinitive, never the gerund:
  `a dividir o áudio… 42%`, `a transcrever… 42%`, `a identificar falantes… 42%`, `a guardar…`.
  The transcript outcome keeps a noun after `durante` (`{outcome} durante a transcrição`).
- **Address:** no `você`. The UI leaves the actor out, and buttons are infinitives, as in the
  European products: `Guardar`, `Eliminar`, `Transferir`, `Cancelar`.
- **Clitics:** enclitic, after the verb (`guardá-lo`, `usá-lo`, `aplicam-se`), never `o guardar`.
- **Vocabulary:** `ficheiro`, `definições`, `eliminar`, `guardar`, `predefinido`, `ecrã`,
  `ligação`, `transferir`, `utilizador`, `aceder`; `aplicação`, not `aplicativo`; `a ligar`,
  not `conectando`. `frontend` never appears in the UI: the user-facing term is `interface do
utilizador` (user decision of 2026-11-13), as the Spanish, French and Hebrew catalogs write it.
- **Gender:** as pt-BR — `falante`/`falantes` is common-gender (`-e`), never `locutor`; a
  sentence that would force a gendered participle is rewritten (`A transcrição e o áudio de
{filename} serão eliminados`).
- **Capitalisation, punctuation, access keys, plurals:** as pt-BR (`one`, `many`, `other`; the
  `…` ellipsis; sentence case; the uppercase status badges).
- **Dates, times, numbers:** formatted by `Intl` from `pt-PT`, never hard-coded in a message.
- **Layout:** the European phrases run about as short as the Brazilian ones; the settings intro
  and the reassign body stay the longest strings.
