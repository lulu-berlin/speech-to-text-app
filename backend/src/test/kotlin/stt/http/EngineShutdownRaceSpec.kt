package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking
import stt.db.Database
import stt.engine.ProcessingEngine
import stt.process.ProcessRunner
import java.util.concurrent.atomic.AtomicBoolean

class EngineShutdownRaceSpec :
    BehaviorSpec({
        given("an application whose engine creation is still in flight when it stops") {
            then("the engine is created before the application can close the Database") {
                val dataDir = tempdir("stt-data").toPath()
                // The engine's ProcessRunner dependency is resolved exactly when the engine's DI entry
                // is created. The test's provider replaces the module's; a subscriber registered before
                // DI is installed runs at ApplicationStopping ahead of DI's cleanup loop, and records
                // whether the engine already existed at that point.
                val engineCreated = AtomicBoolean(false)
                // The engine worker's uncaught escape, captured by the handler the test installs.
                val workerEscaped = AtomicBoolean(false)
                lateinit var db: Database

                testApplication(CoroutineExceptionHandler { _, _ -> workerEscaped.set(true) }) {
                    application {
                        monitor.subscribe(ApplicationStopping) {
                            engineCreated.get().shouldBeTrue()
                        }
                        dependencies {
                            provide<ProcessRunner> {
                                engineCreated.set(true)
                                ProcessRunner()
                            }
                        }
                        sttApp(stt.config.testConfig(dataDir))
                    }
                    startApplication()
                    db = application.dependencies.resolve<Database>()
                }

                // The engine worker must never escape on a closed Database.
                workerEscaped.get().shouldBeFalse()
                db.connection.isClosed.shouldBeTrue()
            }
        }
    })
