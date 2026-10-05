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
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val port = env["STT_PORT"]?.toInt() ?: 8080
            return Config(
                port = port,
                staticDir = env["STT_STATIC_DIR"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) },
            )
        }
    }
}