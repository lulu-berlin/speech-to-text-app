package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.server.plugins.di.dependencies
import stt.db.Database
import java.nio.file.Files

class DatabaseDependencySpec :
    BehaviorSpec({

        given("the application module") {
            `when`("the application starts") {
                then("it opens \$STT_DATA_DIR/stt.db and provides it via DI; a Database the test provides first replaces it unopened") {
                    val dataDir = tempdir("stt-data").toPath()
                    val dbFile = dataDir.resolve("stt.db")
                    verifyCleanStop {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        Files.exists(dbFile).shouldBeTrue()
                        application.dependencies.resolve<Database>().connection.metaData.url
                            .shouldBe("jdbc:sqlite:$dbFile")
                    }

                    var overrideRef: Database? = null
                    val moduleDataDir = tempdir("stt-module-data").toPath()
                    val override = Database.open(tempdir("stt-override-data").toPath())
                    try {
                        verifyCleanStop {
                            application {
                                dependencies.provide<Database> { override }
                                sttApp(stt.config.testConfig(moduleDataDir))
                            }
                            startApplication()
                            overrideRef = application.dependencies
                                .resolve<Database>()
                        }
                    } finally {
                        override.close()
                    }
                    overrideRef.shouldBeSameInstanceAs(override)
                    Files.exists(moduleDataDir.resolve("stt.db")).shouldBeFalse()
                }
            }
        }

        given("an application started with the module") {
            `when`("the application stops") {
                then("the Database provided by DI is closed") {
                    lateinit var db: Database
                    verifyCleanStop {
                        application { sttApp(stt.config.testConfig(tempdir("stt-data").toPath())) }
                        startApplication()
                        db = application.dependencies
                            .resolve<Database>()
                        db.connection.isClosed.shouldBeFalse()
                    }

                    db.connection.isClosed.shouldBeTrue()
                }
            }
        }
    })
