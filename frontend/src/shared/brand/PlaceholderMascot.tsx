import { createContext, useContext, useLayoutEffect } from 'react';
import { StenographerAnimation } from './StenographerAnimation';

// The one-mascot rule of the shell (item 31): while a placeholder (empty, loading or
// connecting) shows the mascot, the header shows the small static logo instead — the
// animated mascot appears at most once on screen. This context is the contract between
// the two: the placeholder components register their presence here, and the header reads
// the count. The provider lives in App (the shell); the context object lives next to the
// artwork so the shared placeholder components can consume it without knowing the app.
export interface PlaceholderMascotHandle {
  // The number of placeholder mascots currently mounted.
  count: number;
  // Registers one mounted placeholder; unregister with the returned function.
  register: () => () => void;
}

export const PlaceholderMascotContext = createContext<PlaceholderMascotHandle | null>(null);

// The placeholder mascot of the empty/loading/connecting states: the artwork at the
// empty-state size, registered with the shell while mounted, so the header knows to swap
// to the static logo. The mascot itself stays decorative (aria-hidden), as everywhere.
export function PlaceholderMascot() {
  const shell = useContext(PlaceholderMascotContext);
  useLayoutEffect(() => (shell === null ? undefined : shell.register()), [shell]);
  return <StenographerAnimation size="empty" />;
}
