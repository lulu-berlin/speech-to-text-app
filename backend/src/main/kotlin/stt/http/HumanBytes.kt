package stt.http

import java.util.Locale

/** Binary units from [humanBytes], smallest first. */
private val BYTE_UNITS = listOf("KiB", "MiB", "GiB", "TiB")

/**
 * [bytes] as an error message names it (item 41): raw bytes below 1 KiB (`1 byte`, `64 bytes`), otherwise at most
 * one decimal in binary units (`1.5 KiB`, `1 GiB`).
 */
internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes ${if (bytes == 1L) "byte" else "bytes"}"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    val rounded = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    return "$rounded ${BYTE_UNITS[unit]}"
}
