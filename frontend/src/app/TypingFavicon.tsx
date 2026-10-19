import { useEffect } from 'react';
// The typing frame is the artwork's own source file — its animation style block lives
// there once, so the favicon cannot drift from the drawing. The idle frame is the still
// derivative of it.
import staticFavicon from '../shared/brand/favicon.svg?url';
import typingFavicon from '../shared/brand/stenographer.svg?url';
import { ConnectionContext } from './ConnectionContext';

// The page's favicon carries the running signal the header gave up (item 59): the animated
// stenographer while a task runs, the still artwork when none does. The desktop app has no
// favicon — its window chrome shows no tab — so there the running state lives in the task
// rows only. The link is the one index.html declares; jsdom never has it, so tests no-op.
// Rendered inside the connection provider, it paints nothing.
export function TypingFavicon() {
  const running = ConnectionContext.useSelector((state) =>
    state.context.tasks.some((task) => task.status === 'RUNNING'),
  );
  useEffect(() => {
    const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    if (link === null) {
      return;
    }
    link.href = running ? typingFavicon : staticFavicon;
  }, [running]);
  return null;
}
