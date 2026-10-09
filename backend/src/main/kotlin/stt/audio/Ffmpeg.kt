package stt.audio

import stt.process.ProcessResult

/** Start of every ffmpeg command: no banner, and no `\r`-rewritten progress line for the line-oriented runner (ADR 0020). */
internal val FFMPEG = listOf("ffmpeg", "-hide_banner", "-nostats")

/** Throws [AudioDecodeException] "ffmpeg failed to [what]: <stderr>" unless this ffmpeg run exited with 0. */
internal fun ProcessResult.expectSuccess(what: String) {
    if (exitCode != 0) throw AudioDecodeException("ffmpeg failed to $what: ${stderr.joinToString("\n").trim()}")
}
