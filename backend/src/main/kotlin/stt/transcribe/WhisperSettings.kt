package stt.transcribe

import stt.config.Device
import java.nio.file.Path

/** The whisper-cli settings shared by every chunk of a task; [threads] null leaves whisper-cli's default. */
data class WhisperSettings(
    val model: Path,
    val language: String,
    val threads: Int?,
    val device: Device,
)
