// The download screen's channel to the main process (roadmap item 50, test item 3): preload.cts
// exposes it as `window.stt`, progress.ts uses it, models.ts produces the progress it carries.
// Ambient on purpose: progress.ts is a classic browser script and cannot import.

/** One model's progress; `total` 0 marks a file that was ready without a download. */
interface ModelProgress {
  key: string;
  file: string;
  received: number;
  total: number;
}

interface SttBridge {
  onProgress(callback: (progress: ModelProgress) => void): void;
  onError(callback: (message: string) => void): void;
  retry(): void;
}

interface Window {
  stt: SttBridge;
}
