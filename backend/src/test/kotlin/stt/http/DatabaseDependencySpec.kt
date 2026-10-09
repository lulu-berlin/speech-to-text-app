package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import stt.config.Config
import stt.config.TEST_SPEAKER_MODEL
import stt.config.TEST_WHISPER_MODEL
import stt.db.Database
import java.nio.file.Files
import java.nio.file.Path

class DatabaseDependencySpec :
    BehaviorSpec({

        given("the application module") {
            `when`("the application starts") {
                then("it opens \$STT_DATA_DIR/stt.db and provides it via DI; a Database the test provides first replaces it unopened") {
                    val dataDir = tempdir("stt-data").toPath()
                    val dbFile = dataDir.resolve("stt.db")
                    testApplication {
                        application {
                            sttApp(
                                Config(
                                    dataDir = dataDir,
                                    speakerModel = Path.of(TEST_SPEAKER_MODEL),
                                    whisperModel = Path.of(TEST_WHISPER_MODEL),
                                ),
                            )
                        }
                        startApplication()
                        Files.exists(dbFile).shouldBeTrue()
                        val db = application.dependencies.resolve<Database>()
                        db.connection.metaData.url
                            .shouldBe("jdbc:sqlite:$dbFile")
                    }

                    val moduleDataDir = tempdir("stt-module-data").toPath()
                    // DI also closes the override on stop (probed 2026-10-06); use {} makes the test's ownership explicit.
                    Database.open(tempdir("stt-override-data").toPath()).use { override ->
                        testApplication {
                            application {
                                dependencies.provide<Database> { override }
                                sttApp(
                                    Config(
                                        dataDir = moduleDataDir,
                                        speakerModel = Path.of(TEST_SPEAKER_MODEL),
                                        whisperModel = Path.of(TEST_WHISPER_MODEL),
                                    ),
                                )
                            }
                            startApplication()
                            application.dependencies.resolve<Database>().shouldBeSameInstanceAs(override)
                            Files.exists(moduleDataDir.resolve("stt.db")).shouldBeFalse()
                        }
                    }
                }
            }
        }

        given("an application started with the module") {
            `when`("the application stops") {
                then("the Database provided by DI is closed") {
                    lateinit var db: Database
                    testApplication {
                        application {
                            sttApp(
                                Config(
                                    dataDir = tempdir("stt-data").toPath(),
                                    speakerModel = Path.of(TEST_SPEAKER_MODEL),
                                    whisperModel = Path.of(TEST_WHISPER_MODEL),
                                ),
                            )
                        }
                        startApplication()
                        db = application.dependencies.resolve<Database>()
                        db.connection.isClosed.shouldBeFalse()
                    }

                    db.connection.isClosed.shouldBeTrue()
                }
            }
        }
    })
