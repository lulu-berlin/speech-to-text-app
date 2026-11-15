# Hebrew (he) — style guide

Companion to `docs/glossary/he.md` and `docs/reviews/he.md`; the standard is the user decision
of 2026-11-12 in `docs/specs/localization.md` (gender-neutral and culturally sensitive wording,
terminology compared with established products).

- **Address:** avoid the gendered imperative. Buttons and actions are action nouns where the
  English has a verb (`שמירה`, `ביטול`, `מחיקה`, `הפעלה`, `השהיה`, `העברה`, `שחזור`,
  `ניסיון נוסף`). Where a sentence must address the reader, use the plural (`בחרו`, `בדקו`,
  `גררו`), which is gender-neutral in form.
- **Gender:** Hebrew nouns and verbs are gendered. Prefer the plural and action nouns; the
  speakers are `קול`/`קולות` (voices) — neutral and dot-free; no dotted inclusive forms are
  needed. The compared products' gendered forms are recorded in the review page
  as evidence, never adopted when a neutral phrasing exists. The comparison is for idiomatic
  wording only: a non-gender-neutral or culturally insensitive term is never accepted.
- **Phases:** the queue line uses bare action nouns after the label (`פיצול… 42%`, `תמלול…
42%`, `הפרדת קולות… 42%`), and the transcript outcome uses the same nouns (`{outcome} בזמן
{phase}`).
- **Capitalization:** sentence case; the status badges stay uppercase words from the catalog
  (`בהמתנה`, `פועל`, `מושהה`, `הושלם`, `נכשל`, `בוטל`) — Hebrew has no letter case, so this is
  the catalog's text as-is.
- **Punctuation and direction:** the Hebrew text uses the Hebrew maqaf `־` sparingly and the
  ASCII en dash `–` as the English catalog does; `…` for ellipses; `״…״` for quotation. The
  document is RTL, and the transcript's Latin text keeps its own direction (item 76). Numbers,
  times and dates are formatted by `Intl` from `he-IL`, never hard-coded.
- **Access keys:** the `<u>` letter is the editor shortcut, and the app follows it. Save is
  `<u>ש</u>מירה` and Discard `<u>ב</u>יטול`; the access-key check rejects a shared letter, an
  underline that is not one letter, and the reserved letters A C V X Y Z N Q T W.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU` and the whisper language
  codes stay untranslated. The UI locale's own names come from `docs/api/ui-locales.json`.
- **Plurals:** Hebrew has `one`, `two` and `other`; the ICU messages carry those (the `many` branch is inert under current CLDR)
  (`{count, plural, one {קטע אחד נבחר} two {שני קטעים נבחרו} many {# קטעים נבחרו} other {# קטעים
נבחרו}}`).
- **Layout:** allow for the Hebrew text's length and the RTL mirroring; the review page's
  longest strings are the settings intro and the reassign body.
