package stt.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.config.Device
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path

/** Transcribes one chunk with one whisper-cli run through [runner] (ADR 0004). */
class ChunkTranscriber internal constructor(
    private val runner: ProcessRunner,
    /** The whisper-cli executable. A seam so tests can stand in a script for whisper-cli. */
    private val executable: String,
) {
    constructor(runner: ProcessRunner) : this(runner, "whisper-cli")

    /**
     * Runs whisper-cli over [audio] and returns its segments, offsets relative to [audio].
     * A non-zero exit fails with [TranscriptionException] carrying the exit code and the full stderr:
     * whisper-cli prints its error anywhere in stderr (before ~70 usage lines on a bad argument).
     */
    suspend fun transcribe(
        model: Path,
        audio: Path,
        language: String,
        threads: Int?,
        device: Device,
        outputPrefix: Path,
        prompt: String?,
    ): List<WhisperSegment> {
        val command = whisperCommand(model, audio, language, threads, device, outputPrefix, prompt, executable)
        val result = runner.run(command)
        if (result.exitCode != 0) {
            throw TranscriptionException(
                "whisper-cli failed on $audio (exit ${result.exitCode}):\n${result.stderr.joinToString("\n")}",
            )
        }
        val json = Path.of("$outputPrefix.json")
        return parseSegments(withContext(Dispatchers.IO) { Files.readString(json) })
    }
}
