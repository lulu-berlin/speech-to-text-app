package stt.outputs

import java.util.Locale

/**
 * Formats [ms] like the oracle's `fmt_srt`: `HH:MM:SS,mmm`. Hours are zero-padded to two digits and never wrap
 * (100 h → `100:00:00,000`).
 */
internal fun fmtSrt(ms: Long): String =
    String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms % 3_600_000 / 60_000, ms % 60_000 / 1000, ms % 1000)
