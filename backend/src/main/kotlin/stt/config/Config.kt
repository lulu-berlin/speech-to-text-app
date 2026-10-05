package stt.config

import java.nio.file.Path

/**
 * Application configuration, read from `STT_*` environment variables by [fromEnv].
 * Unset or empty `STT_STATIC_DIR` disables static serving. `STT_PORT` defaults to 8080
 * when unset; an invalid value fails fast (throws) instead of falling back.
 */
class Config(
    val port: Int = 8080,
    val staticDir: Path? = null,
) {
    companion object {
        private const val PORT_LIMIT_ERROR =
            "STT_PORT must be an integer between 1 and 65535, got \"%s\""

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val rawPort = env["STT_PORT"]
            val port = if (rawPort == null) 8080 else parsePort(rawPort)
            return Config(
                port = port,
                staticDir = env["STT_STATIC_DIR"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) },
            )
        }

        private fun parsePort(raw: String): Int {
            val value =
                raw.toIntOrNull()
                    ?: throw IllegalArgumentException(PORT_LIMIT_ERROR.format(raw))
            return value
        }
    }
}
