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

/**
 * The defaults `V6__settings.sql` seeds and `DELETE /api/settings` restores (roadmap item 72, ADR 0062): auto,
 * whisper's and the speaker model's own thread count (null), auto-detect, auto speakers, 8 max speakers, and no
 * CPU-fallback notice. Changing a default requires the migration and this constant together; the reset route spec
 * pins these values.
 */
val DEFAULT_SETTINGS =
    Settings(
        device = Device.Auto,
        threads = null,
        defaultLanguage = "auto",
        speakersMode = SpeakersMode.AUTO,
        speakersCount = null,
        defaultMaxSpeakers = 8,
    )
