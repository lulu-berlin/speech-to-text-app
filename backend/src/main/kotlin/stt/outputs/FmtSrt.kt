package stt.outputs

/** Formats [ms] like the oracle's `fmt_srt`: `HH:MM:SS,mmm` (see [formatTimestamp]). */
internal fun fmtSrt(ms: Long): String = formatTimestamp(ms, ',')
