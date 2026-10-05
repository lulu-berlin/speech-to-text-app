package stt.config

import java.nio.file.Path

/**
 * Application configuration, read from `STT_*` environment variables by [fromEnv]
 * (added with the env-reading tests). Test code constructs instances directly.
 */
class Config(
    val staticDir: Path? = null,
)