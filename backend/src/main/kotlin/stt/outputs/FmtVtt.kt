package stt.outputs

/** Formats [ms] like the oracle's `fmt_vtt`: `HH:MM:SS.mmm` (see [formatTimestamp]). */
internal fun fmtVtt(ms: Long): String = formatTimestamp(ms, '.')
