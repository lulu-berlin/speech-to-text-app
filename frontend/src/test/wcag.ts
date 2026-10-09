import { expect } from 'vitest';

// WCAG 2.x AA thresholds: 4.5:1 for normal text (1.4.3), 3:1 for user-interface components
// such as focus indicators (1.4.11).
export const AA_TEXT = 4.5;
export const AA_NON_TEXT = 3;

// One sRGB channel of a hex color (#rrggbb), linearized as in WCAG 2.x.
function channel(hex: string, start: number): number {
  const c = parseInt(hex.slice(start, start + 2), 16) / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

// WCAG 2.x relative luminance. Tokens are plain #rrggbb, so they can be measured.
export function luminance(hex: string): number {
  expect(hex).toMatch(/^#[0-9a-f]{6}$/i);
  return 0.2126 * channel(hex, 1) + 0.7152 * channel(hex, 3) + 0.0722 * channel(hex, 5);
}

// WCAG 2.x contrast ratio, (lighter + 0.05) / (darker + 0.05).
export function contrast(foreground: string, background: string): number {
  const a = luminance(foreground);
  const b = luminance(background);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}
