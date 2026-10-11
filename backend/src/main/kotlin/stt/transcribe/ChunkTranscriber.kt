package stt.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.config.Device
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path

/** Transcribes one chunk with one whisper-cli run through [runner] (ADR 0004); the [Transcriber] implementation. */
class ChunkTranscriber internal constructor(
    private val runner: ProcessRunner,
    /** The whisper-cli executable. A seam so tests can stand in a script for whisper-cli. */
    private val executable: String,
) : Transcriber {
    constructor(runner: ProcessRunner) : this(runner, "whisper-cli")

    /**
     * Runs whisper-cli over [audio] and returns its segments, offsets relative to [audio].
     * Fails with [TranscriptionException] carrying the full stderr on a non-zero exit (with the exit code),
     * a missing JSON output, or a malformed one: whisper-cli prints its error anywhere in stderr (before ~70
     * usage lines on a bad argument) and exits 0 on unreadable audio without writing JSON. With a GPU chosen,
     * whisper-cli 1.9.2 that cannot use it prints `no GPU found`, runs on the CPU and exits 0; that successful run
     * then calls [onGpuUnavailable] (item 32).
     */
    override suspend fun transcribe(
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
        onProgress: (Double) -> Unit,
        onGpuUnavailable: suspend () -> Unit,
    ): List<WhisperSegment> {
        val command = whisperCommand(settings, audio, outputPrefix, prompt, executable)
        val result = runner.run(command, onStderrLine = { line -> parseProgress(line)?.let(onProgress) })
        val stderr = result.stderr.joinToString("\n")
        if (result.exitCode != 0) {
            throw TranscriptionException("whisper-cli failed on $audio (exit ${result.exitCode}):\n$stderr")
        }
        val json = jsonOutput(outputPrefix)
        val output =
            withContext(Dispatchers.IO) { if (Files.exists(json)) Files.readString(json) else null }
                ?: throw TranscriptionException("whisper-cli JSON output $json is missing after transcribing $audio:\n$stderr")
        val parsed =
            try {
                parseSegments(output)
            } catch (e: IllegalArgumentException) {
                // parseSegments reports malformed output (parse errors, wrong shapes, missing legacy keys) as IllegalArgumentException.
                throw TranscriptionException("whisper-cli JSON output $json is malformed (${e.message}):\n$stderr")
            }
        if (settings.device is Device.Gpu && result.stderr.any { it.contains(NO_GPU_LINE) }) onGpuUnavailable()
        return parsed
    }
}

/** whisper-cli 1.9.2's line (libwhisper `%s: no GPU found`) when no GPU backend could be initialized. */
private const val NO_GPU_LINE = "no GPU found"
