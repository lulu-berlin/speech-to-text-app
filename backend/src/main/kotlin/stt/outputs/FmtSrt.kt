package stt.outputs

/** Formats [ms] as `HH:MM:SS,mmm` (see [formatTimestamp]). */
internal fun fmtSrt(ms: Long): String = formatTimestamp(ms, ',')
