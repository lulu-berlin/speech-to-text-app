// jsdom helpers for the tests that assert computed styles.

// The element a primitive rendered, or a loud failure. SVG elements are not HTMLElements,
// so this accepts any Element (styles are read via getComputedStyle either way).
export function required(element: Element | null | undefined): Element {
  if (!(element instanceof Element)) {
    throw new Error('The element did not render');
  }
  return element;
}

// jsdom reports colours as rgb(), so a token is converted the same way.
export function asRgb(hex: string): string {
  const value = Number.parseInt(hex.slice(1), 16);
  return `rgb(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255})`;
}

// jsdom normalises lengths to px at the default root font size (1rem = 16px) but keeps
// shorthands such as `gap` exactly as written, so both forms can be compared with the
// token they came from.
export function px(length: string): string {
  return `${Number.parseFloat(length) * 16}px`;
}
