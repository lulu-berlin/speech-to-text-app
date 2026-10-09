package stt.config

import java.nio.file.Path

/** A non-empty STT_DATA_DIR value for tests that only need configuration to load. */
internal const val TEST_DATA_DIR = "/var/lib/stt"

/** A non-empty STT_SPEAKER_MODEL value for tests that only need configuration to load (never opened). */
internal const val TEST_SPEAKER_MODEL = "/var/lib/stt-models/campplus.onnx"

/** A non-empty STT_WHISPER_MODEL value for tests that only need configuration to load (never opened). */
internal const val TEST_WHISPER_MODEL = "/var/lib/stt-models/ggml-large-v3-turbo-q5_0.bin"

/** The variables every configuration needs; a test adds or removes the ones it is about. */
internal val TEST_REQUIRED_ENV =
    mapOf(
        "STT_DATA_DIR" to TEST_DATA_DIR,
        "STT_SPEAKER_MODEL" to TEST_SPEAKER_MODEL,
        "STT_WHISPER_MODEL" to TEST_WHISPER_MODEL,
    )

/**
 * A [Config] for tests that only need configuration to load: [dataDir] plus the test model paths
 * ([TEST_SPEAKER_MODEL], [TEST_WHISPER_MODEL]), which are never opened.
 */
internal fun testConfig(
    dataDir: Path,
    staticDir: Path? = null,
): Config = Config(dataDir, Path.of(TEST_SPEAKER_MODEL), Path.of(TEST_WHISPER_MODEL), staticDir = staticDir)

/**
 * The real CAM++ model path from the environment's `$STT_SPEAKER_MODEL`, read through [Config.fromEnv] as the app
 * reads it (`Integration` tests only). STT_DATA_DIR is required by [Config] but unused here.
 */
internal fun realSpeakerModelPath(): Path = Config.fromEnv(System.getenv() + ("STT_DATA_DIR" to TEST_DATA_DIR)).speakerModel
