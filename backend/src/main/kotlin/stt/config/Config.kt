package stt.config

import java.nio.file.Path

/**
 * Application configuration, read from `STT_*` environment variables by [fromEnv].
 * Unset or empty `STT_STATIC_DIR` disables static serving. `STT_PORT` defaults to 8080
 * when unset; an invalid value fails fast (throws) instead of falling back.
 * `STT_DATA_DIR` is required: unset or empty fails fast (ADR 0017); it has no default.
 * `STT_SPEAKER_MODEL` (the CAM++ ONNX model, ADR 0003) is required: unset or empty fails fast.
 * `STT_WHISPER_MODEL` (the whisper.cpp model file, ADR 0008) is required the same way.
 * `STT_HOST` defaults to `0.0.0.0` (every installation served on the LAN); the desktop app
 * (roadmap item 50) sets it to `127.0.0.1` so its API is never reachable from the network.
 * The compute device and the thread count are not configuration: they are stored settings (roadmap item 32),
 * so `STT_DEVICE` and `STT_THREADS` are not read.
 * `STT_MAX_UPLOAD_BYTES` caps one upload's size (roadmap item 41); it defaults to 1 GiB, and any value that is
 * not a positive integer fails fast.
 * `STT_STOP_ON_STDIN_CLOSE=1` makes the backend stop once its stdin closes: the desktop app's graceful quit on
 * Windows, which has no signal to send (roadmap item 49). Any other value, or none, leaves stdin unread, so
 * `gradle run` (whose stdin is empty) and a console start keep running.
 */
data class Config(
    val dataDir: Path,
    val speakerModel: Path,
    val whisperModel: Path,
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val staticDir: Path? = null,
    val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    val stopOnStdinClose: Boolean = false,
) {
    companion object {
        private const val PORT_LIMIT_ERROR =
            "STT_PORT must be an integer between 1 and 65535, got \"%s\""
        private const val MAX_UPLOAD_BYTES_ERROR =
            "STT_MAX_UPLOAD_BYTES must be a positive integer (bytes), got \"%s\""
        private const val DATA_DIR_MISSING_ERROR =
            "STT_DATA_DIR must be set to the data directory (it holds stt.db)"
        private const val SPEAKER_MODEL_MISSING_ERROR =
            "STT_SPEAKER_MODEL must be set to the CAM++ speaker-embedding ONNX model file"
        private const val WHISPER_MODEL_MISSING_ERROR =
            "STT_WHISPER_MODEL must be set to the whisper.cpp model file"

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val dataDir = env["STT_DATA_DIR"]
            require(!dataDir.isNullOrEmpty()) { DATA_DIR_MISSING_ERROR }
            val speakerModel = env["STT_SPEAKER_MODEL"]
            require(!speakerModel.isNullOrEmpty()) { SPEAKER_MODEL_MISSING_ERROR }
            val whisperModel = env["STT_WHISPER_MODEL"]
            require(!whisperModel.isNullOrEmpty()) { WHISPER_MODEL_MISSING_ERROR }
            val rawPort = env["STT_PORT"]
            val port = if (rawPort == null) 8080 else parseIntIn(rawPort, 1..65535, PORT_LIMIT_ERROR)
            val maxUploadBytes = env["STT_MAX_UPLOAD_BYTES"]?.let { parseMaxUploadBytes(it) } ?: DEFAULT_MAX_UPLOAD_BYTES
            return Config(
                dataDir = Path.of(dataDir),
                speakerModel = Path.of(speakerModel),
                whisperModel = Path.of(whisperModel),
                port = port,
                host = env["STT_HOST"]?.takeIf { it.isNotEmpty() } ?: "0.0.0.0",
                staticDir = env["STT_STATIC_DIR"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) },
                maxUploadBytes = maxUploadBytes,
                stopOnStdinClose = env["STT_STOP_ON_STDIN_CLOSE"] == "1",
            )
        }

        /** [raw] as a positive byte count; otherwise fails fast with [MAX_UPLOAD_BYTES_ERROR] formatted with [raw]. */
        private fun parseMaxUploadBytes(raw: String): Long {
            val value = raw.toLongOrNull()
            if (value == null || value <= 0) throw IllegalArgumentException(MAX_UPLOAD_BYTES_ERROR.format(raw))
            return value
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
    }
}

/** The upload size limit when `STT_MAX_UPLOAD_BYTES` is unset: 1 GiB (roadmap item 41). */
const val DEFAULT_MAX_UPLOAD_BYTES: Long = 1_073_741_824L
