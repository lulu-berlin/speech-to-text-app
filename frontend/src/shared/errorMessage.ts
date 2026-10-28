// The server's (or the browser's) message of an unknown thrown value: an Error's message,
// or the value's string form for something else. Views show it under their own title.
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
