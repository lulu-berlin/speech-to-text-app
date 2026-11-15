import { createContext, useContext } from 'react';
import type { TextDirection } from './uiLocales';

// The app's UI direction (item 88): the shell and its Radix-based controls (the seek slider,
// the per-task menus) follow it, so an RTL app language mirrors them. The default is LTR for
// the components rendered outside the app's locale provider (the shared UI's own tests).
export const UiDirectionContext = createContext<TextDirection>('ltr');

export function useUiDirection(): TextDirection {
  return useContext(UiDirectionContext);
}
