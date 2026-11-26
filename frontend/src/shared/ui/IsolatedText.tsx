import { standaloneDirection } from '../textDirection';

// User content (a speaker name, a filename) inside text of another direction (item 76):
// isolated, so it cannot reorder what surrounds it, and in its own standalone direction.
export function IsolatedText({ text }: { text: string }) {
  return <bdi dir={standaloneDirection(text)}>{text}</bdi>;
}
