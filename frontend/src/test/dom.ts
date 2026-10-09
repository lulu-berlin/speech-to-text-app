// jsdom helpers for the tests that assert computed styles.

// The element a primitive rendered, or a loud failure.
export function required(element: Element | null | undefined): HTMLElement {
  if (!(element instanceof HTMLElement)) {
    throw new Error('The element did not render');
  }
  return element;
}

// jsdom normalises lengths to px at the default root font size (1rem = 16px) but keeps
// shorthands such as `gap` exactly as written, so both forms can be compared with the
// token they came from.
export function px(length: string): string {
  return `${Number.parseFloat(length) * 16}px`;
}
