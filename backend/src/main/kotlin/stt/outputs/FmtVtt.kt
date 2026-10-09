package stt.outputs

import java.util.Locale

/**
 * Formats [ms] like the oracle's `fmt_vtt`: `HH:MM:SS.mmm` (same as [fmtSrt] with a dot before the milliseconds).
 */
internal fun fmtVtt(ms: Long): String =
    String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms % 3_600_000 / 60_000, ms % 60_000 / 1000, ms % 1000)
