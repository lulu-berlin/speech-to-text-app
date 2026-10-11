package stt.db

import stt.config.Device

/**
 * The app settings of roadmap item 32: the device and thread count for the next task the engine starts,
 * and the upload form's defaults (language, speakers mode, max speakers). [threads] null keeps whisper-cli's
 * and the speaker model's own defaults; [speakersCount] is set only with [speakersMode] FIXED, as for a task.
 * [deviceFallback] is the CPU-fallback notice: the chosen GPU the engine found unusable and switched to the CPU,
 * null without a notice; it is not something the user sets, so a stored `PUT` (which builds it null) clears it.
 */
data class Settings(
    val device: Device,
    val threads: Int?,
    val defaultLanguage: String,
    val speakersMode: SpeakersMode,
    val speakersCount: Int?,
    val defaultMaxSpeakers: Int,
    val deviceFallback: Device? = null,
)
