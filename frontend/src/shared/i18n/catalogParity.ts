import {
  isArgumentElement,
  isPluralElement,
  isSelectElement,
  isTagElement,
  parse,
  type MessageFormatElement,
  type PluralElement,
} from '@formatjs/icu-messageformat-parser';

/** One way a locale's catalog differs from the reference catalog. */
export interface CatalogViolation {
  locale: string;
  key: string;
  rule:
    | 'missing-key'
    | 'stale-key'
    | 'invalid-icu'
    | 'placeholder'
    | 'missing-plural-category'
    | 'unknown-locale';
  detail?: string;
}

interface Used {
  arguments: Set<string>;
  tags: Set<string>;
  plurals: PluralElement[];
}

function collect(elements: readonly MessageFormatElement[], used: Used): void {
  for (const element of elements) {
    if (isArgumentElement(element)) {
      used.arguments.add(element.value);
    } else if (isTagElement(element)) {
      used.tags.add(element.value);
      collect(element.children, used);
    } else if (isPluralElement(element)) {
      used.arguments.add(element.value);
      used.plurals.push(element);
      for (const option of Object.values(element.options)) {
        collect(option.value, used);
      }
    } else if (isSelectElement(element)) {
      used.arguments.add(element.value);
      for (const option of Object.values(element.options)) {
        collect(option.value, used);
      }
    }
  }
}

function usedOf(elements: readonly MessageFormatElement[]): Used {
  const used: Used = { arguments: new Set(), tags: new Set(), plurals: [] };
  collect(elements, used);
  return used;
}

function difference(left: Set<string>, right: Set<string>): string[] {
  return [...left].filter((value) => !right.has(value));
}

/**
 * The catalog parity check (roadmap item 88): every key of the reference catalog, with the
 * same ICU placeholders and tags, valid ICU, and every plural category the locale requires.
 * The reference is the English catalog; a locale message missing an `other` clause is a
 * missing category, not a syntax error (the parser is asked to tolerate it).
 */
export function checkCatalog(
  locale: string,
  reference: Record<string, string>,
  messages: Record<string, string>,
): CatalogViolation[] {
  const violations: CatalogViolation[] = [];
  const parseOrReport = (
    source: string,
    key: string,
    label: string,
  ): MessageFormatElement[] | null => {
    try {
      return parse(source, { requiresOtherClause: false });
    } catch (error) {
      violations.push({
        locale,
        key,
        rule: 'invalid-icu',
        detail: `${label}: ${error instanceof Error ? error.message : String(error)}`,
      });
      return null;
    }
  };

  for (const key of Object.keys(reference)) {
    if (!(key in messages)) {
      violations.push({ locale, key, rule: 'missing-key' });
      continue;
    }
    const referenceAst = parseOrReport(reference[key] ?? '', key, 'reference');
    const messageAst = parseOrReport(messages[key] ?? '', key, 'message');
    if (referenceAst === null || messageAst === null) {
      continue;
    }
    const expected = usedOf(referenceAst);
    const actual = usedOf(messageAst);
    const differences: string[] = [];
    const missingArguments = difference(expected.arguments, actual.arguments);
    const extraArguments = difference(actual.arguments, expected.arguments);
    const missingTags = difference(expected.tags, actual.tags);
    const extraTags = difference(actual.tags, expected.tags);
    if (missingArguments.length > 0) {
      differences.push(`missing placeholders: ${missingArguments.join(', ')}`);
    }
    if (extraArguments.length > 0) {
      differences.push(`unexpected placeholders: ${extraArguments.join(', ')}`);
    }
    if (missingTags.length > 0) {
      differences.push(`missing tags: ${missingTags.join(', ')}`);
    }
    if (extraTags.length > 0) {
      differences.push(`unexpected tags: ${extraTags.join(', ')}`);
    }
    if (differences.length > 0) {
      violations.push({ locale, key, rule: 'placeholder', detail: differences.join('; ') });
    }

    for (const plural of actual.plurals) {
      const rules = new Intl.PluralRules(locale, {
        type: plural.pluralType === 'ordinal' ? 'ordinal' : 'cardinal',
      });
      for (const category of rules.resolvedOptions().pluralCategories) {
        if (!(category in plural.options)) {
          violations.push({ locale, key, rule: 'missing-plural-category', detail: category });
        }
      }
    }
  }

  for (const key of Object.keys(messages)) {
    if (!(key in reference)) {
      violations.push({ locale, key, rule: 'stale-key' });
    }
  }

  return violations.sort((a, b) => a.key.localeCompare(b.key) || a.rule.localeCompare(b.rule));
}

/**
 * The shipped catalogs against the UI locales of `docs/api/ui-locales.json` (item 88): a catalog
 * named after a locale the app does not ship is reported, and every other catalog is checked
 * against the English one. A UI locale without a catalog yet is the wave's open work, not a
 * violation here.
 */
export function checkShippedCatalogs(
  catalogs: Readonly<Record<string, Record<string, string>>>,
  uiLocaleCodes: readonly string[],
): CatalogViolation[] {
  const english = catalogs['en'];
  if (english === undefined) {
    throw new Error('the English reference catalog en.json is missing');
  }
  return Object.entries(catalogs).flatMap(([locale, messages]) =>
    uiLocaleCodes.includes(locale)
      ? checkCatalog(locale, english, messages)
      : [{ locale, key: `${locale}.json`, rule: 'unknown-locale' as const }],
  );
}
