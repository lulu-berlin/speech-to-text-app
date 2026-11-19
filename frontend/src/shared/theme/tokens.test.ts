import { describe, expect, it } from 'vitest';
import colors from '../../../../ui-theme/colors.json';
import { darkTheme, lightTheme } from './theme';

// The shared colour tokens (ADR 0068): one value per role and theme, so the browser and the TUI
// cannot drift apart. This checks that both themes take every colour from that file - a role the
// file holds and the theme does not, or a theme value the file does not hold, fails here.

type Mode = 'light' | 'dark';
type Tokens = { [role: string]: string | string[] | Tokens };

// The value `tokens` holds for `mode`: a leaf `{ light, dark }` pair picks its side, arrays stay
// whole, and a group of roles is resolved role by role.
function resolve(tokens: Tokens, mode: Mode): unknown {
  if (typeof tokens === 'string' || Array.isArray(tokens)) {
    return tokens;
  }
  if ('light' in tokens && 'dark' in tokens) {
    return tokens[mode];
  }
  return Object.fromEntries(
    Object.entries(tokens).map(([role, value]) => [role, resolve(value as Tokens, mode)]),
  );
}

describe('the shared colour tokens', () => {
  it('are every colour of both themes, and nothing else', () => {
    expect(lightTheme.colors).toEqual(resolve(colors.colors, 'light'));
    expect(darkTheme.colors).toEqual(resolve(colors.colors, 'dark'));
    expect(lightTheme.illustration).toEqual(resolve(colors.illustration, 'light'));
    expect(darkTheme.illustration).toEqual(resolve(colors.illustration, 'dark'));
  });
});
