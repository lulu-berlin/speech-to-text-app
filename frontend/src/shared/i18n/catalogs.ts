// The shipped message catalogs (roadmap item 88), keyed by UI locale code: every
// `src/locales/<code>.json`. A new language ships by adding its catalog, with no code change.
// The catalog map is built from this glob. Vitest's transform cache bakes the file list into
// the module, so a catalog added after the cache was written stays invisible until the cache
// turns over; the checks read the directory themselves (src/test/catalogs.ts) where that
// matters.
const files = import.meta.glob<Record<string, string>>('../../locales/*.json', {
  eager: true,
  import: 'default',
});

export const CATALOGS: Readonly<Record<string, Record<string, string>>> = Object.fromEntries(
  Object.entries(files).map(([path, messages]) => [
    (path.split('/').pop() ?? '').replace(/\.json$/, ''),
    messages,
  ]),
);
