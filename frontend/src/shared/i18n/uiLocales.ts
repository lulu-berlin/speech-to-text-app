// The app's own languages (roadmap item 88): the shipped UI locales and the mapping from a
// system language to one. The list is contract data (docs/api/ui-locales.json, ADR 0029), so
// every locale, its own name and its whisper transcription code are readable without the app.
import { uiLocales } from '../api/fixtures';
import { CATALOGS } from './catalogs';
import type { TextDirection } from '../textDirection';

export interface UiLocale {
  code: string;
  name: string;
  nativeName: string;
  language: string;
  direction: TextDirection;
  formatLocale: string;
}

function textDirection(value: string): TextDirection {
  if (value === 'ltr' || value === 'rtl') {
    return value;
  }
  throw new Error(`ui-locales.json: unknown direction "${value}"`);
}

export const UI_LOCALES: UiLocale[] = uiLocales.locales.map((locale) => ({
  ...locale,
  direction: textDirection(locale.direction),
}));

export const DEFAULT_UI_LOCALE: string = uiLocales.default;

/**
 * The UI locales the app can switch to: those of `ui-locales.json` that ship a catalog
 * (item 88). The others are the wave's open work and are not offered.
 */
export const SHIPPED_UI_LOCALES: UiLocale[] = UI_LOCALES.filter((locale) =>
  Object.hasOwn(CATALOGS, locale.code),
);

const byLowerCaseCode = new Map(
  UI_LOCALES.map((locale) => [locale.code.toLowerCase(), locale.code]),
);
const byBase = new Map<string, string[]>();
for (const locale of UI_LOCALES) {
  const base = locale.code.toLowerCase().split('-')[0] ?? '';
  byBase.set(base, [...(byBase.get(base) ?? []), locale.code]);
}

// A language subtag whose system tag names no shipped UI locale directly: the split
// languages choose a variant, `jv` is the BCP 47 tag for whisper's `jw`, and a language
// shipped as exactly one locale takes it.
function baseMatch(base: string, region: string | undefined): string | undefined {
  switch (base) {
    case 'zh':
      return region === 'tw' || region === 'hk' || region === 'mo' || region === 'hant'
        ? 'zh-Hant'
        : 'zh-Hans';
    case 'es':
      return region === undefined || region === 'es' ? 'es-ES' : 'es-419';
    case 'pt':
      return region === 'br' ? 'pt-BR' : 'pt-PT';
    case 'jv':
      return 'jw';
    default: {
      const shipped = byBase.get(base);
      return shipped?.length === 1 ? shipped[0] : undefined;
    }
  }
}

/**
 * The shipped UI locale a system tag names, with the tag normalized (trimmed, `_` as `-`), or
 * undefined when it names none. The split languages' variant rule applies when the exact tag is
 * not shipped.
 */
function matchTag(raw: string): { tag: string; code: string } | undefined {
  const tag = raw.trim().replace('_', '-');
  if (tag === '') {
    return undefined;
  }
  const lower = tag.toLowerCase();
  const exact = byLowerCaseCode.get(lower);
  if (exact !== undefined) {
    return { tag, code: exact };
  }
  const [base = '', region] = lower.split('-');
  const match = baseMatch(base, region);
  return match === undefined ? undefined : { tag, code: match };
}

/**
 * The shipped UI locale a system language list asks for (`navigator.languages`, in the
 * reader's order), or the default when none is shipped. An exact tag wins, then the split
 * languages' variant rules, then a base language shipped as one locale; a locale without a
 * catalog is passed over for the next tag.
 */
export function resolveUiLocale(tags: readonly string[]): string {
  for (const raw of tags) {
    const match = matchTag(raw);
    if (match !== undefined && Object.hasOwn(CATALOGS, match.code)) {
      return match.code;
    }
  }
  return DEFAULT_UI_LOCALE;
}

/** The reader's system languages, preferred first (`navigator.languages`, then `language`). */
export function systemLanguageTags(): string[] {
  return [...navigator.languages, navigator.language];
}

/**
 * The UI locale the app runs in (item 88): the stored App language when it ships a catalog,
 * otherwise the one the system's languages resolve to.
 */
export function effectiveUiLocale(appLanguage: string | null, tags: readonly string[]): string {
  return appLanguage !== null && Object.hasOwn(CATALOGS, appLanguage)
    ? appLanguage
    : resolveUiLocale(tags);
}

/** The text direction of a UI locale; LTR when the code is not one of the shipped locales. */
export function uiDirection(code: string): TextDirection {
  return UI_LOCALES.find((locale) => locale.code === code)?.direction ?? 'ltr';
}

function hasRegion(tag: string): boolean {
  try {
    return new Intl.Locale(tag).region !== undefined;
  } catch {
    return false;
  }
}

/**
 * The full BCP 47 tag formatting uses for a UI locale (roadmap item 88). A UI locale is a
 * language identification; formatting always needs a region. The system's full tag is used when
 * it names the same UI locale (the split languages' variant rule included), and the locale's
 * canonical region otherwise.
 */
export function formatLocaleFor(uiLocale: string, tags: readonly string[]): string {
  const code = byLowerCaseCode.get(uiLocale.toLowerCase());
  const locale = UI_LOCALES.find((candidate) => candidate.code === code);
  if (locale === undefined) {
    throw new Error(`ui-locales.json: unknown locale "${uiLocale}"`);
  }
  for (const raw of tags) {
    const match = matchTag(raw);
    if (match !== undefined && match.code === locale.code && hasRegion(match.tag)) {
      return match.tag;
    }
  }
  return locale.formatLocale;
}
