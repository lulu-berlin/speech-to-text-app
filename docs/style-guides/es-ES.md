# Spanish (Spain) — style guide

Companion to `docs/glossary/es-ES.md` and `docs/reviews/es-ES.md`; the standard and policy are
the user decisions of 2026-11-12 in `docs/specs/localization.md`.

- **Address:** `tú` with the imperative (`Comprueba la dirección`, `Arrastra archivos`).
- **Gender:** no generic masculine. Speaker terms are `voz`/`voces` (the app handles voices,
  not people), a turn is `intervención`, and participles agree with the file or task, never with
  a person. `hablante` from the compared products is evidence only, never used.
- **Phases:** the queue line uses the bare noun (`Transcripción… 42 %`); the transcript outcome
  uses the noun with its article (`Error durante la transcripción`,
  `Cancelación durante la fragmentación`).
- **Typography:** inverted opening marks (`¿…?`, `¡…!`), angular quotes `«»`, a space before
  `%` in messages (`42 %`), the ellipsis `…`, and the em dash `—` in the queue lines.
- **Capitalization:** sentence case; status badges uppercase (`EN COLA`, `EN CURSO`, `EN PAUSA`,
  `COMPLETADA`, `FALLIDA`, `CANCELADA`).
- **Access keys:** the `<u>` letter is the editor shortcut: `Guardar` → `G`, `Descartar` → `D`
  (English `S` and `D`). The access key check rejects a shared letter, a multi-letter underline
  and the reserved A C V X Y Z N Q T W.
- **Products and codes:** `Speech-to-Text`, formats, `CPU`, `GPU`, `Hilos` as the product
  writes it, and the whisper language codes stay untranslated; `upload` is `subir`.
- **Plurals:** Spanish has `one`, `many` and `other`; `many` (millions) takes `de`:
  `# de segmentos seleccionados`.
- **Dates, times, numbers:** formatted by `Intl` from the format locale (`es-ES`), never in a
  message. The queue's phase percent is formatted by the same `Intl` call.
- **Layout:** allow roughly 30% expansion; the longest strings are the settings intro, the
  diarizing phase and the reassign body.
