package stt.transcribe

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.process.ProcessResult
import stt.process.ProcessRunner
import java.nio.file.Path

/** One GPU the startup device probe found: its index (whisper-cli's `--device N`) and its description. */
data class GpuDevice(
    val index: Int,
    val description: String,
)

/**
 * The startup device probe (roadmap item 32): runs whisper-cli once and parses the GPUs it reports on
 * stderr. whisper-cli prints them while initializing the context, before it reads the audio — the input
 * only has to exist (`/dev/null` decodes to nothing), so the probe costs one model load and any exit code
 * still carries the lines. A failed probe (whisper-cli missing, ...) reports no GPU, so the app still
 * starts and the Settings page offers CPU and auto.
 */
class DeviceProbe internal constructor(
    private val runner: ProcessRunner,
    private val model: Path,
    /** The whisper-cli executable. A seam so tests can stand in a script for whisper-cli (like [ChunkTranscriber]). */
    private val executable: String,
) {
    constructor(runner: ProcessRunner, model: Path) : this(runner, model, "whisper-cli")

    /** The GPUs whisper-cli reported, in line order; empty when it reports none or the probe fails. */
    suspend fun gpus(): List<GpuDevice> {
        val result =
            try {
                runner.run(listOf(executable, "-m", model.toString(), "-f", "/dev/null"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Device probe failed; assuming no GPU: {}", e.toString())
                ProcessResult(exitCode = -1, stdout = emptyList(), stderr = emptyList())
            }
        return parseGpuLines(result.stderr)
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(DeviceProbe::class.java)
    }
}

/** The marker of whisper-cli's per-GPU line, from libwhisper's `%s: found GPU device %zu: %s (type: %d, cnt: %d)` (identical in 1.8.4 and 1.9.2). */
private const val GPU_LINE_MARKER = "found GPU device "

/**
 * Parses the GPUs of whisper-cli's stderr: the lines `…: found GPU device <n>: <description> (type: <t>, cnt: <c>)`,
 * in line order. The CPU backend's `device <n>: CPU (type: 0)` line is not a GPU line, and any other line is ignored.
 */
internal fun parseGpuLines(stderr: List<String>): List<GpuDevice> =
    stderr.mapNotNull { line ->
        val start = line.indexOf(GPU_LINE_MARKER)
        if (start < 0) return@mapNotNull null
        val rest = line.substring(start + GPU_LINE_MARKER.length)
        val index = rest.substringBefore(":").toIntOrNull() ?: return@mapNotNull null
        GpuDevice(index, rest.substringAfter(": ").substringBefore(" (type: "))
    }
