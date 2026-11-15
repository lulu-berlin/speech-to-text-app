# Contributing

## Proposing a translation change

The app's interface strings live in one catalog per UI locale, `frontend/src/locales/<code>.json`,
keyed by the same keys as `frontend/src/locales/en.json`. Each shipped locale also has:

- `docs/reviews/<code>.md` — its review page: every string with its source text and translation,
  the products compared and their wording, and the gender-neutrality and cultural-sensitivity
  audit;
- `docs/glossary/<code>.md` — its shared vocabulary and the evidence for each term;
- `docs/style-guides/<code>.md` — its register, punctuation and layout rules.

To propose a wording change:

1. Edit the locale's catalog (`frontend/src/locales/<code>.json`). Keep the key, its placeholders
   (`{filename}`, `{count, plural, …}`) and any `<u>`/`<link>` tags unchanged.
2. Update the same string's evidence on `docs/reviews/<code>.md` (its Compared and Audit cells)
   and, when the change settles a term, the glossary and style guide. A row's evidence is the
   wording an established product uses; record the product, its wording and its popularity rank,
   and never invent a comparison.
3. Regenerate the review pages: `node docs/generate-reviews.ts` writes every page's source,
   translation, Glossary column and row order from the catalogs, the glossaries and the recorded
   evidence. Commit the generated pages; `docs/check-reviews.ts` fails when a page is not what the
   generator writes, so a hand edit cannot drift in.
4. Run the checks from the repository root:

   ```
   pnpm --dir frontend test
   pnpm --dir docs test
   ```

   The catalog checks require every shipped locale to have every key with the right placeholders,
   ICU and plural categories, and `docs/check-reviews.ts` requires each review page to match its
   catalog row for row and to be its generated form (it fails with the page, line and key when one
   is stale).
5. Open a pull request. A change to a catalog, its review page, its glossary and its style guide
   belongs in one pull request; the pull request's CI run is the merge gate.

The product name, file extensions, model names and the whisper language codes are never
translated. A new locale ships by adding its catalog and the three documents above, with no code
change; `docs/api/ui-locales.json` registers its own name, direction and format locale.
