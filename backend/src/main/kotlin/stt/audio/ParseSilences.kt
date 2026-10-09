package stt.audio

import kotlin.math.roundToLong

private val SILENCE_START = Regex("""silence_start: (\S+)""")
private val SILENCE_END = Regex("""silence_end: (\S+)""")

/**
 * Parses ffmpeg `silencedetect` [stderr] lines into silences, rounded to the millisecond; other lines are ignored.
 * A trailing `silence_start` without `silence_end` ends at [durationMs].
 */
internal fun parseSilences(
    stderr: List<String>,
    durationMs: Long,
): List<Silence> {
    val silences = mutableListOf<Silence>()
    var openStartMs: Long? = null
    for (line in stderr) {
        val start = SILENCE_START.find(line)
        val end = SILENCE_END.find(line)
        if (start != null) {
            openStartMs = secondsToMs(start.groupValues[1])
        } else if (end != null && openStartMs != null) {
            silences += Silence(openStartMs, secondsToMs(end.groupValues[1]))
            openStartMs = null
        }
    }
    if (openStartMs != null) silences += Silence(openStartMs, durationMs)
    return silences
}

private fun secondsToMs(seconds: String): Long = (seconds.toDouble() * 1000).roundToLong()
