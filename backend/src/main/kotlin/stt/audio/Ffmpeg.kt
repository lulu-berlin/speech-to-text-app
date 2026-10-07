package stt.audio

/** Start of every ffmpeg command: no banner, and no `\r`-rewritten progress line for the line-oriented runner (ADR 0020). */
internal val FFMPEG = listOf("ffmpeg", "-hide_banner", "-nostats")
