package stt.outputs

/** Formats [ms] as `HH:MM:SS.mmm` (see [formatTimestamp]). */
internal fun fmtVtt(ms: Long): String = formatTimestamp(ms, '.')
