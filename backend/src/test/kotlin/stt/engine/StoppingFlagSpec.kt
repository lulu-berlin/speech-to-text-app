package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Roadmap item 49's review finding: `ProcessRunnerStoppingSpec` injects the stopping flag, so only this test shows
 * that the production wiring — the engine module's subscription to Ktor's stop event — actually sets it.
 */
class StoppingFlagSpec :
    BehaviorSpec({
        given("an application wired with the engine module's stopping flag") {
            `when`("the server starts to stop") {
                then("the flag is set") {
                    val stopping = AtomicBoolean(false)
                    lateinit var app: io.ktor.server.application.Application
                    testApplication {
                        application {
                            app = this
                            markStopping(stopping)
                        }
                        startApplication()
                        app.monitor.raise(ApplicationStopPreparing, app.environment)
                    }

                    stopping.get() shouldBe true
                }
            }
        }
    })
