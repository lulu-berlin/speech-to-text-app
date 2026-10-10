package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import stt.db.Database
import stt.process.ProcessRunner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The DI-teardown interleaving this item is about: Ktor's DI stop sequence closes the [Database]
 * while the engine's DI entry is mid-creation (the application job is cancelled only after the DI
 * cleanup loop, which skips not-yet-created entries — see the item's roadmap file). The fix
 * (ADR 0038) makes that impossible: the engine is created as part of startup, so by the time
 * [ApplicationStopping] runs the engine's entry is complete, DI joins its worker before it closes
 * the Database, and no engine code ever runs on the closed Database.
 *
 * The deterministic-red reproduction (the engine's ProcessRunner dependency held until DI's cleanup
 * had passed the engine's key) is recorded in this branch's history (`fdee4bf`): it cannot run
 * against the fixed code, because gating the engine's creation would block `startApplication` itself.
 */
class EngineShutdownRaceSpec :
    BehaviorSpec({
        given("an application whose engine's DI entry would be mid-creation when the stop closes the Database") {
            `when`("the application stops") {
                then("the engine was already created, its worker did not escape, and the Database closed after it") {
                    val dataDir = tempdir("stt-data").toPath()
                    // The engine's ProcessRunner dependency is resolved exactly when the engine's DI
                    // entry is created; the test's provider replaces the module's. A subscriber
                    // registered before `dependencies { }` installs DI runs at ApplicationStopping
                    // ahead of DI's cleanup loop: it proves the engine existed before the Database
                    // could close (mid-creation at that point is the bug's signature).
                    val engineCreated = AtomicBoolean(false)

                    verifyCleanStop {
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
                    }
                }
            }
        }
    })
