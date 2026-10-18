package stt.transcribe

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.process.ProcessResult
import stt.process.ProcessRunner
import java.io.IOException
import java.nio.file.Path

/** One physical GPU the startup device probe found: its index in ggml's Vulkan enumeration and its name. */
data class GpuDevice(
    val index: Int,
    val name: String,
)

/**
 * The probe's outcome: the physical GPUs whisper-cli enumerated (none is a true "no GPU"), or the launch failure
 * that stopped the probe (item 60: a failed probe is a failure, never a "no GPU" claim).
 */
sealed interface DeviceProbeOutcome {
    data class Probed(
        val gpus: List<GpuDevice>,
    ) : DeviceProbeOutcome

    /** whisper-cli could not be launched; [message] is the JVM's reason (missing, not executable, …). */
    data class LaunchFailed(
        val message: String,
    ) : DeviceProbeOutcome
}

/**
 * The startup device probe (roadmap items 32, 45): runs whisper-cli once and parses the physical Vulkan GPUs
 * its ggml backend enumerates on stderr. whisper-cli prints them while initializing, before it reads the audio —
 * the input only has to exist (`/dev/null` decodes to nothing), so the probe costs one model load and any exit
 * code still carries the lines. A launch failure (`IOException`: whisper-cli missing or not executable) is logged
 * with its throwable and reported as [DeviceProbeOutcome.LaunchFailed], so the app still starts and the Settings page
 * offers CPU and auto; any other throwable propagates and fails startup (item 60), and cancellation is rethrown.
 */
class DeviceProbe internal constructor(
    /** The runner seam: production passes [ProcessRunner.run]; a test throws or answers directly. */
    private val run: suspend (List<String>) -> ProcessResult,
    private val model: Path,
    /** The whisper-cli executable. A seam so tests can stand in a script for whisper-cli (like [ChunkTranscriber]). */
    private val executable: String,
) {
    constructor(
        runner: ProcessRunner,
        model: Path,
    ) : this({ command -> runner.run(command) }, model, "whisper-cli")

    internal constructor(
        runner: ProcessRunner,
        model: Path,
        executable: String,
    ) : this({ command -> runner.run(command) }, model, executable)

    /** The probe's outcome; see [DeviceProbeOutcome]. */
    suspend fun probe(): DeviceProbeOutcome {
        val result =
            try {
                run(listOf(executable, "-m", model.toString(), "-f", "/dev/null"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn("Device probe failed; {}", e.message ?: e.toString(), e)
                return DeviceProbeOutcome.LaunchFailed(e.message ?: e.toString())
            }
        return DeviceProbeOutcome.Probed(parseVulkanDeviceLines(result.stderr))
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(DeviceProbe::class.java)
    }
}

/**
 * ggml-vulkan's per-device line, `ggml_vulkan: %zu = %s (%s) | uma: %d | …` (index, device name, driver name;
 * whisper.cpp 1.8.4). A device name may itself end in parentheses (`AMD … (RADV REMBRANDT)`), so the name is
 * everything up to the last parenthesized group before ` | uma: `.
 */
private val VULKAN_DEVICE_LINE = Regex("""ggml_vulkan: (\d+) = (.+) \([^()]*\) \| uma: """)

/**
 * Parses the physical GPUs of whisper-cli's stderr: ggml-vulkan's per-device lines, in line order; GPU N here is
 * ggml device `Vulkan<N>`, which `--device N` selects. Its `Found N Vulkan devices:` header, libwhisper's
 * `device …`/`found GPU device …` lines (printed only up to the selected device) and any other line are ignored —
 * including one whose index is no machine integer, so a garbled line never throws ([stt.Main] probes before it
 * starts the server) and the probe reports the devices it could read.
 */
internal fun parseVulkanDeviceLines(stderr: List<String>): List<GpuDevice> =
    stderr.mapNotNull { line ->
        val match = VULKAN_DEVICE_LINE.find(line) ?: return@mapNotNull null
        val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        GpuDevice(index, match.groupValues[2])
    }
