package stt.config

import java.nio.file.Path

/** A non-empty STT_DATA_DIR value for tests that only need configuration to load. */
internal const val TEST_DATA_DIR = "/var/lib/stt"

/** A non-empty STT_SPEAKER_MODEL value for tests that only need configuration to load (never opened). */
internal const val TEST_SPEAKER_MODEL = "/var/lib/stt-models/campplus.onnx"

/**
 * The real CAM++ model path from the environment's `$STT_SPEAKER_MODEL`, read through [Config.fromEnv] as the app
 * reads it (`Integration` tests only). STT_DATA_DIR is required by [Config] but unused here.
 */
internal fun realSpeakerModelPath(): Path = Config.fromEnv(System.getenv() + ("STT_DATA_DIR" to TEST_DATA_DIR)).speakerModel
