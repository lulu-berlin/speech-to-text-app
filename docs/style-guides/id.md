# Indonesian (id) — style guide

Companion to `docs/glossary/id.md` and `docs/reviews/id.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Address:** buttons and actions use the bare verb (`Batal`, `Hapus`, `Putar`, `Jeda`), and
  running instructions use the same imperative (`Periksa alamat`, `klik untuk memilih`). The reader
  is addressed only by that imperative: no pronoun or title is used (`Anda` occurs nowhere in the
  catalog), and English "your" is rendered impersonally (`pencarian dan filter`). Indonesian verbs
  carry no grammatical gender.
- **Gender:** `pembicara` names the voice's source and `ucapan` what is said; neither marks a
  gender, and no sentence states one.
- **Punctuation:** ASCII `.` `,` `:` `?` as the English catalog; the ellipsis `…`; the em dash
  `—`; quotation marks `"…"`. Labels keep the English form without a final stop (`Format file tidak
didukung`, `Tidak ditemukan`).
- **Phases:** nouns after the label (`Pemecahan audio`, `Transkripsi`, `Deteksi pembicara`); the
  outcome line is `{outcome} saat {phase}`, with a position `{outcome} saat {phase} pada {time}`.
- **Capitalization:** labels and sentences use sentence case; the status badges are uppercase, as
  the English badges are and the badge renders the string verbatim.
- **Status badges:** `DALAM ANTREAN`, `BERJALAN`, `DIJEDA`, `SELESAI`, `GAGAL`, `DIBATALKAN`; the
  history filter reuses `Selesai`, `Gagal` and `Dibatalkan` in sentence case.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `Simpan (<u>S</u>)` and
  `Buang (<u>D</u>)` — because the check requires one underlined letter per button; no two share
  one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `thread` and the whisper
  language codes; `antarmuka pengguna`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json`.
- **Plurals:** Indonesian has only the `other` ICU category, and the message carries just it.
- **Dates, times, numbers:** formatted by `Intl` from `id-ID`, never hard-coded; the percent sign of
  `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Indonesian runs longer than English: `settings.intro` is 174 code points against 136,
  then `transcript.reassign.body` (150 against 120) and `queue.fallback.detail` (117 against 114).
