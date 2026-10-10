import { useCallback, useEffect, useState } from 'react';
import { darkTheme, lightTheme, themeFor } from './theme';
import type { ThemeTokens } from './theme';

// Dark mode follows the OS unless the user picks a mode; the choice is stored (ADR 0033).
export type ThemeMode = 'auto' | 'light' | 'dark';

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

// The token set for the stored choice, falling back to the OS preference in auto mode.
export function resolveTheme(mode: ThemeMode, prefersDark: boolean): ThemeTokens {
  return mode === 'light' ? lightTheme : mode === 'dark' ? darkTheme : themeFor(prefersDark);
}

// The stored mode and a cycle action; saving happens in an effect so the updater stays pure.
export function useThemeMode(): [ThemeMode, () => void] {
  const [mode, setMode] = useState<ThemeMode>(loadThemeMode);
  const cycle = useCallback(() => {
    setMode((current) => cycleThemeMode(current));
  }, []);
  useEffect(() => {
    saveThemeMode(mode);
  }, [mode]);
  return [mode, cycle];
}
