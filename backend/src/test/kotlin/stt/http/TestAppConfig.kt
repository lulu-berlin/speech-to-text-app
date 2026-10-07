package stt.http

import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import stt.config.Config
import stt.config.TEST_SPEAKER_MODEL
import java.nio.file.Path

/** A Config for application tests: a fresh temp data directory, so the app can open its database. */
internal fun TestConfiguration.testConfig(staticDir: Path? = null): Config =
    Config(dataDir = tempdir("stt-data").toPath(), speakerModel = Path.of(TEST_SPEAKER_MODEL), staticDir = staticDir)
