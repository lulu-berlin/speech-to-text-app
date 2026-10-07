package stt.transcribe

/** whisper-cli 1.9.2 `-pp` stderr line; the percentage is padded to width 3 (`progress =  27%`). */
private val PROGRESS = Regex("""^whisper_print_progress_callback: progress = +(\d+)%$""")

/** The chunk fraction (0.0–1.0) reported by a whisper-cli `-pp` stderr [line], or null for any other line. */
fun parseProgress(line: String): Double? =
    PROGRESS
        .find(line)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?.div(100.0)
