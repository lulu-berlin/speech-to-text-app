package stt.config

import java.nio.file.Path

/**
 * Application configuration, read from `STT_*` environment variables by [fromEnv].
 * Unset or empty `STT_STATIC_DIR` disables static serving. `STT_PORT` defaults to 8080
 * when unset; an invalid value fails fast (throws) instead of falling back.
 * `STT_DATA_DIR` is required: unset or empty fails fast (ADR 0017); it has no default.
 * `STT_DEVICE` (`auto`, `cpu`, `gpu`, or a GPU index >= 0) defaults to `auto` when unset; any other value,
 * empty included, fails fast.
 * `STT_THREADS` (an integer >= 1) is unset by default, leaving whisper-cli's own default; any other value,
 * empty included, fails fast.
 * `STT_SPEAKER_MODEL` (the CAM++ ONNX model, ADR 0003) is required: unset or empty fails fast.
 */
class Config(
    val dataDir: Path,
    val speakerModel: Path,
    val port: Int = 8080,
    val staticDir: Path? = null,
    val device: Device = Device.Auto,
    val threads: Int? = null,
) {
    companion object {
        private const val PORT_LIMIT_ERROR =
            "STT_PORT must be an integer between 1 and 65535, got \"%s\""
        private const val DATA_DIR_MISSING_ERROR =
            "STT_DATA_DIR must be set to the data directory (it holds stt.db)"
        private const val DEVICE_ERROR =
            "STT_DEVICE must be auto, cpu, gpu, or a GPU index >= 0, got \"%s\""
        private const val THREADS_ERROR =
            "STT_THREADS must be an integer >= 1, got \"%s\""
        private const val SPEAKER_MODEL_MISSING_ERROR =
            "STT_SPEAKER_MODEL must be set to the CAM++ speaker-embedding ONNX model file"

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val dataDir = env["STT_DATA_DIR"]
            require(!dataDir.isNullOrEmpty()) { DATA_DIR_MISSING_ERROR }
            val speakerModel = env["STT_SPEAKER_MODEL"]
            require(!speakerModel.isNullOrEmpty()) { SPEAKER_MODEL_MISSING_ERROR }
            val rawPort = env["STT_PORT"]
            val port = if (rawPort == null) 8080 else parseIntIn(rawPort, 1..65535, PORT_LIMIT_ERROR)
            return Config(
                dataDir = Path.of(dataDir),
                speakerModel = Path.of(speakerModel),
                port = port,
                staticDir = env["STT_STATIC_DIR"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) },
                device = env["STT_DEVICE"]?.let { parseDevice(it) } ?: Device.Auto,
                threads = env["STT_THREADS"]?.let { parseIntIn(it, 1..Int.MAX_VALUE, THREADS_ERROR) },
            )
        }

        /** [raw] as an integer within [range]; otherwise fails fast with [error] formatted with [raw]. */
        private fun parseIntIn(
            raw: String,
            range: IntRange,
            error: String,
        ): Int {
            val value = raw.toIntOrNull()
            if (value == null || value !in range) throw IllegalArgumentException(error.format(raw))
            return value
        }

        private fun parseDevice(raw: String): Device =
            when (raw) {
                "auto" -> {
                    Device.Auto
                }

                "cpu" -> {
                    Device.Cpu
                }

                "gpu" -> {
                    Device.Gpu(0)
                }

                else -> {
                    Device.Gpu(parseIntIn(raw, 0..Int.MAX_VALUE, DEVICE_ERROR))
                }
            }
    }
}
