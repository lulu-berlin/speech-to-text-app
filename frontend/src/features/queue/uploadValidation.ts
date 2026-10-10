// The upload extension allow-list (item 12 contract): files outside it are rejected
// client-side with an unsupported-format error, before any request. The extension is
// checked case-insensitively; the content type is ignored (browsers send generic types).
export const ALLOWED_EXTENSIONS = [
  'mp3',
  'wav',
  'flac',
  'ogg',
  'oga',
  'opus',
  'm4a',
  'aac',
  'wma',
  'aif',
  'aiff',
  'amr',
  'webm',
  'mp4',
  'm4v',
  'mov',
  'mkv',
  'mka',
  '3gp',
] as const;

export function extensionOf(filename: string): string {
  const dot = filename.lastIndexOf('.');
  return dot === -1 ? '' : filename.slice(dot + 1).toLowerCase();
}

export function isSupportedFormat(filename: string): boolean {
  return (ALLOWED_EXTENSIONS as readonly string[]).includes(extensionOf(filename));
}
