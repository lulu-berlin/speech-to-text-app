import { useSyncExternalStore } from 'react';

const QUERY = '(prefers-color-scheme: dark)';

// jsdom has no matchMedia; browsers always do.
function mediaQuery(): MediaQueryList | undefined {
  return typeof window.matchMedia === 'function' ? window.matchMedia(QUERY) : undefined;
}

// Subscribes to the media query so the theme follows live OS changes.
function subscribe(onChange: () => void): () => void {
  const media = mediaQuery();
  media?.addEventListener('change', onChange);
  return () => media?.removeEventListener('change', onChange);
}

function getSnapshot(): boolean {
  return mediaQuery()?.matches ?? false;
}

export function usePrefersDark(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}
