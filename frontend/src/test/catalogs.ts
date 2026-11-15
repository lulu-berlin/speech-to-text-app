/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import uiLocales from '../../../docs/api/ui-locales.json';

// The module's own directory, from Vitest's runner: under jsdom `import.meta.url` is not a
// file URL, so the path is built from `import.meta.dirname`.
const LOCALES_DIR = join(import.meta.dirname, '..', 'locales');

/**
 * Every catalog file in `src/locales`, keyed by its locale code (item 88), read from disk on
 * each call. `import.meta.glob` is not used: Vitest caches the transformed test module with the
 * file list baked in, so a newly added catalog went unchecked until the test file changed.
 */
export function shippedCatalogs(): Record<string, Record<string, string>> {
  return Object.fromEntries(
    readdirSync(LOCALES_DIR)
      .filter((file) => file.endsWith('.json'))
      .map((file) => [
        file.replace(/\.json$/, ''),
        JSON.parse(readFileSync(join(LOCALES_DIR, file), 'utf8')) as Record<string, string>,
      ]),
  );
}

/** The shipped UI locale codes, from the contract data `docs/api/ui-locales.json`. */
export const UI_LOCALE_CODES: readonly string[] = uiLocales.locales.map((locale) => locale.code);
