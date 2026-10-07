package stt.config

import java.nio.file.Path

/**
 * Application configuration, read from `STT_*` environment variables by [fromEnv].
 * Unset or empty `STT_STATIC_DIR` disables static serving. `STT_PORT` defaults to 8080
 * when unset; an invalid value fails fast (throws) instead of falling back.
 * `STT_DATA_DIR` is required: unset or empty fails fast (ADR 0017); it has no default.
 * `STT_DEVICE` (`auto`, `cpu`, `gpu`, or a GPU index >= 0) defaults to `auto` when unset; any other value,
 * empty included, fails fast.
 */
class Config(
    val dataDir: Path,
    val port: Int = 8080,
    val staticDir: Path? = null,
    val device: Device = Device.Auto,
) {
    companion object {
        private const val PORT_LIMIT_ERROR =
            "STT_PORT must be an integer between 1 and 65535, got \"%s\""
        private const val DATA_DIR_MISSING_ERROR =
            "STT_DATA_DIR must be set to the data directory (it holds stt.db)"
        private const val DEVICE_ERROR =
            "STT_DEVICE must be auto, cpu, gpu, or a GPU index >= 0, got \"%s\""

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val dataDir = env["STT_DATA_DIR"]
            require(!dataDir.isNullOrEmpty()) { DATA_DIR_MISSING_ERROR }
            val rawPort = env["STT_PORT"]
            val port = if (rawPort == null) 8080 else parsePort(rawPort)
            return Config(
                dataDir = Path.of(dataDir),
                port = port,
                staticDir = env["STT_STATIC_DIR"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) },
                device = env["STT_DEVICE"]?.let { parseDevice(it) } ?: Device.Auto,
            )
        }

        private fun parsePort(raw: String): Int {
            val value = raw.toIntOrNull()
            if (value == null || value !in 1..65535) {
                throw IllegalArgumentException(PORT_LIMIT_ERROR.format(raw))
            }
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
                    val index = raw.toIntOrNull()
                    if (index == null || index < 0) throw IllegalArgumentException(DEVICE_ERROR.format(raw))
                    Device.Gpu(index)
                }
            }
    }
}
