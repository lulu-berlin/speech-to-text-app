// The upload extension allow-list (item 12 contract): files outside it are rejected
// client-side with an unsupported-format error, before any request. The extension is
// checked case-insensitively; the content type is ignored (browsers send generic types).
// The list is the contract file docs/api/allowed-extensions.json, which the backend also
// pins (AllowedExtensionsContractSpec), so one source feeds both sides.
import allowedExtensions from '../../../../docs/api/allowed-extensions.json';

export const ALLOWED_EXTENSIONS: readonly string[] = allowedExtensions;

export function extensionOf(filename: string): string {
  const dot = filename.lastIndexOf('.');
  return dot === -1 ? '' : filename.slice(dot + 1).toLowerCase();
}

export function isSupportedFormat(filename: string): boolean {
  return ALLOWED_EXTENSIONS.includes(extensionOf(filename));
}
