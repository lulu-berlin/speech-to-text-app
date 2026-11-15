# Javanese (jw) — style guide

Companion to `docs/glossary/jw.md` and `docs/reviews/jw.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Register:** krama, the polite form, because an interface addresses a stranger: the function
  words `mboten`, `saged`, `kedah`, `dhateng`, `saking`, `sapunika`, `menika`, and the stems
  `mlampah`, `pados`, `mriki`, `inggil`, `ngandhap`, `saderengipun`, `bikak`, `dipunleren`. The
  applicative suffix is krama `-aken`, never ngoko `-ake`; the ngoko stems `mlaku`, `golek`,
  `kene`, `dhuwur`, `ngisor`, `sadurunge` never appear. The stem `owah` keeps its common form in
  the derived verbs (`Owahi`, `Ngowahi`) because the krama `ewah` is rare in interface text; the
  glossary records the choice. Technical nouns keep the established Indonesian loans
  (`transkripsi`, `setelan`, `riwayat`, `antrian`), and the interface loans the glossary lists
  (`status`, `filter`, `tema`, `notifikasi`, `aplikasi`, `layanan`, `kolom`, `permanen`, `klik`,
  `pilihan`, `lanjutan`) are accepted as Javanese technical writing uses them.
- **Address:** buttons and actions use the bare imperative (`Batal`, `Busak`, `Puter`, `Leren`),
  and running instructions use the same form (`Priksa alamat`, `pilih`). Javanese imperatives carry
  no grammatical gender, and no pronoun or title addresses the reader.
- **Gender:** `pamicara` names the voice's source and `pocapan` what is said; neither marks a
  gender, and no sentence states one.
- **Punctuation:** ASCII `.` `,` `:` `?` as the English catalog; the ellipsis `…`; the em dash
  `—`; quotation marks `"…"`. Labels keep the English form without a final stop (`Format berkas
mboten dipunsengkuyung`, `Mboten kapanggih`).
- **Phases:** nouns after the label (`Pemecahan audio`, `Transkripsi`, `Deteksi pamicara`); the
  outcome line is `{outcome} nalika {phase}`, with a position `{outcome} nalika {phase} ing {time}`.
- **Status badges:** `ING ANTRIAN`, `MLAKU`, `DIPUNLEREN`, `RAMPUNG`, `GAGAL`, `DIPUNBATAL`, as the
  English badges are uppercase; the history filter uses the sentence-case forms.
- **Access keys:** the editor's buttons show the shortcut in parentheses — `Simpen (<u>S</u>)` and
  `Buang (<u>D</u>)` — because the check requires one underlined letter per button; no two share
  one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `thread` and the whisper
  language codes; `antarmuka panganggo`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json` (`Basa Jawa` for this one).
- **Plurals:** Javanese has only the `other` ICU category, and the message carries just it.
- **Dates, times, numbers:** formatted by `Intl` from `jv-ID`, never hard-coded; the percent sign of
  `{stage} {percent}%` sits in the message, as in the English catalog.
- **Layout:** Javanese runs longer than English: `settings.intro` is 172 code points against 136,
  then `transcript.reassign.body` (169 against 120) and `queue.fallback.detail` (131 against 114).
