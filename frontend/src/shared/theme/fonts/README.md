# IBM Plex Sans (self-hosted)

`ibm-plex-sans-latin-wght-normal.woff2` — the latin subset of the variable font, weight axis
100–700, 45,712 bytes, sha256
`e2291e842cf5af167122a22881a740c7f2dda7716f1e8cd76680264f4a859470`.

- Source: `@fontsource-variable/ibm-plex-sans@5.3.0` (npm), file
  `files/ibm-plex-sans-latin-wght-normal.woff2`; Fontsource built it from Google Fonts
  (`github.com/google/fonts`, `ofl/ibmplexsans`, version v23).
- Licence: SIL Open Font License 1.1, Copyright 2019 IBM Corp. — the full text is `OFL.txt`
  in this directory.
- Committed as an asset rather than installed as a dependency, so the app ships without a
  network fetch and the Nix package carries it (ADR 0026, roadmap item 24).
- Chosen over the three static files (400/500/600: 22,588 + 24,184 + 24,252 = 71,024 bytes)
  because the single variable file is smaller and covers the whole weight range.
