// Detects a pathological CI runner early (roadmap item 85, discovered). CI run #544's Windows
// journey took 64 minutes, and #570's and #576's were stopped at the 90-minute job timeout with ten
// of sixty specs done. The recorded per-spec durations say why: whisper ran 7–9× slower on those
// VMs while every UI-only spec kept its usual second or two, so the runner's transcription speed —
// not the app — collapsed. This reads the same signal from the journey's first specs, so the run
// can abort in minutes instead of sitting out the job's timeout.
//
// The ratio is the run's own, not a recorded per-platform table: on `03` against the UI-only specs
// healthy runs measure 0.7–2.4×, the 64-minute VM of #544 4.7× and #605's starved VM 24.7×, and a
// machine that is slower overall (or faster, as macOS with Metal is) moves both sides together.

/** The probe specs: one that transcribes and four that only drive the UI, all in the first five. */
export const PROBE_SPECS = {
  transcription: ['03-upload-queue.spec.ts'],
  ui: [
    '01-app-loads.spec.ts',
    '02-mascot-and-loading.spec.ts',
    '04-reorder.spec.ts',
    '05-transcript.spec.ts',
  ],
} as const;

/**
 * Above this, the runner is the pathological kind run #605 measured. Measured on `03` against the
 * UI-only specs: healthy runs 0.7–2.4×, the 64-minute VM of #544 4.7×, #605 24.7× — so 10 sits with
 * ~2× margin on both sides, and one spec earlier than probing `03`+`06` would.
 */
export const SLOW_RUNNER_FACTOR = 10;

export interface SpecTiming {
  /** The spec's file, absolute or repository-relative; matched by suffix. */
  file: string;
  ms: number;
}

export interface TranscriptionRatio {
  factor: number;
  transcriptionMs: number;
  uiMs: number;
}

/**
 * The journey's transcription time over its UI-only time, measured on [timings]; `null` until every
 * probe spec has run, or when no UI time was recorded (a zero divisor says nothing about the runner).
 */
export function transcriptionRatio(timings: SpecTiming[]): TranscriptionRatio | null {
  const total = (names: readonly string[]): number | null => {
    let ms = 0;
    for (const name of names) {
      const timing = timings.find((candidate) => candidate.file.endsWith(name));
      if (timing === undefined) return null;
      ms += timing.ms;
    }
    return ms;
  };
  const transcriptionMs = total(PROBE_SPECS.transcription);
  const uiMs = total(PROBE_SPECS.ui);
  if (transcriptionMs === null || uiMs === null || uiMs === 0) return null;
  return { factor: transcriptionMs / uiMs, transcriptionMs, uiMs };
}
