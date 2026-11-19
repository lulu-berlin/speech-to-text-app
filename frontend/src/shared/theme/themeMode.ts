import type { ThemeMode } from '../api/types';
import { darkTheme, lightTheme, themeFor } from './theme';
import type { ThemeTokens } from './theme';

// The theme mode the service stores for every frontend (roadmap item 67, ADR 0068); auto
// follows the OS.
export type { ThemeMode };

// The browser's copy of the last mode it drew in: the first paint uses it until the stored
// settings arrive.
const STORAGE_KEY = 'stt-theme-mode';

// The cycle order: auto → light → dark → auto.
export function cycleThemeMode(mode: ThemeMode): ThemeMode {
  return mode === 'auto' ? 'light' : mode === 'light' ? 'dark' : 'auto';
}

export function loadThemeMode(): ThemeMode {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === 'light' || stored === 'dark' ? stored : 'auto';
}

export function saveThemeMode(mode: ThemeMode): void {
  if (mode === 'auto') {
    localStorage.removeItem(STORAGE_KEY);
  } else {
    localStorage.setItem(STORAGE_KEY, mode);
  }
}

// The token set for the mode, falling back to the OS preference in auto mode.
export function resolveTheme(mode: ThemeMode, prefersDark: boolean): ThemeTokens {
  return mode === 'light' ? lightTheme : mode === 'dark' ? darkTheme : themeFor(prefersDark);
}
