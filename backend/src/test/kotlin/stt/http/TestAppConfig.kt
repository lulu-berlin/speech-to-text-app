package stt.http

import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import stt.config.Config
import java.nio.file.Path

/** A Config for application tests: a fresh temp data directory, so the app can open its database. */
internal fun TestConfiguration.testConfig(staticDir: Path? = null): Config =
    Config(dataDir = tempdir("stt-data").toPath(), staticDir = staticDir)
