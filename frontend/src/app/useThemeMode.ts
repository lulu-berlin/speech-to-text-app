import { useCallback, useEffect, useState } from 'react';
import { settingsUpdateOf } from '../shared/api/client';
import type { ThemeMode } from '../shared/api/types';
import { cycleThemeMode, loadThemeMode, saveThemeMode } from '../shared/theme/themeMode';
import { SettingsContext } from './SettingsContext';

// The theme mode (roadmap item 67, ADR 0068): the service stores it for every frontend. The page
// draws in the stored mode, writes a change as part of the whole settings, and follows another
// frontend's change through settings-updated. Until the settings are read it draws in the
// last-known mode, which it keeps in localStorage; the control can write only once they are.
export function useThemeMode(): { mode: ThemeMode; cycle: () => void; ready: boolean } {
  const stored = SettingsContext.useSelector((state) => state.context.settings);
  const settings = SettingsContext.useActorRef();
  const [lastKnown] = useState(loadThemeMode);
  const mode = stored?.themeMode ?? lastKnown;

  useEffect(() => {
    saveThemeMode(mode);
  }, [mode]);

  const cycle = useCallback(() => {
    if (stored !== null) {
      settings.send({
        type: 'SAVE',
        settings: { ...settingsUpdateOf(stored), themeMode: cycleThemeMode(stored.themeMode) },
      });
    }
  }, [settings, stored]);

  return { mode, cycle, ready: stored !== null };
}
