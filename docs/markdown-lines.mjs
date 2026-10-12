// Shared by the documentation checks (roadmap item 35): the lines of a Markdown text
// outside fenced code blocks, with their 1-based numbers.
export function* proseLines(markdown) {
  let inFence = false;
  for (const [index, line] of markdown.split('\n').entries()) {
    if (line.trimStart().startsWith('```')) {
      inFence = !inFence;
    } else if (!inFence) {
      yield { number: index + 1, line };
    }
  }
}
