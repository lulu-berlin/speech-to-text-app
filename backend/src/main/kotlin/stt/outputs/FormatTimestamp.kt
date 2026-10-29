package stt.outputs

import java.util.Locale

/**
 * Formats [ms] as `HH:MM:SS{separator}mmm` with [separator] `,` for srt and `.`
 * for vtt. Hours are zero-padded to two digits and never wrap (100 h → `100:00:00,000`).
 */
internal fun formatTimestamp(
    ms: Long,
    separator: Char,
): String =
    String.format(
        Locale.ROOT,
        "%02d:%02d:%02d%c%03d",
        ms / 3_600_000,
        ms % 3_600_000 / 60_000,
        ms % 60_000 / 1000,
        separator,
        ms % 1000,
    )
