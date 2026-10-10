package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import stt.db.Database
import stt.process.ProcessRunner
import kotlin.time.Duration.Companion.seconds

/** A dependency the test registers after the app's own, so DI cleans it up last: after the [Database]. */
class StopHook

class EngineShutdownRaceSpec :
    BehaviorSpec({
        given("an application whose engine creation is still in flight when it stops") {
            then("no engine code runs on a closed Database") {
                val dataDir = tempdir("stt-data").toPath()
                // The engine's creation is held open until the stop sequence has run the Database's
                // cleanup: its ProcessRunner dependency waits on the gate. Only the StopHook hook
                // opens the gate (the StopHook is cleaned up after the Database, reverse registration
                // order), so without the fix the engine is necessarily created against the already
                // closed Database and its worker escapes.
                val creationGate = CompletableDeferred<Unit>()
                // The engine worker's uncaught escape, captured by the handler the test installs. The
                // StopHook cleanup blocks the stopping thread until the worker has escaped or (after
                // the fix) the engine proves it can finish without touching the closed Database.
                val workerFailure = CompletableDeferred<Throwable>()
                lateinit var db: Database

                testApplication(CoroutineExceptionHandler { _, e -> workerFailure.complete(e) }) {
                    application {
                        dependencies {
                            provide<StopHook> { StopHook() } cleanup {
                                creationGate.complete(Unit)
                                runBlocking { withTimeoutOrNull(5.seconds) { workerFailure.await() } }
                            }
                            // Replaces the module's ProcessRunner in the engine's creation path.
                            provide<ProcessRunner> {
                                creationGate.await()
                                ProcessRunner()
                            }
                        }
                        sttApp(stt.config.testConfig(dataDir))
                    }
                    startApplication()
                    db = application.dependencies.resolve<Database>()
                    application.dependencies.resolve<StopHook>()
                }

                // The escaped engine worker ran engine code on the closed Database: the same
                // SQLException the flaky Nix run reported. The fix must make the engine start with
                // the application, so nothing escapes.
                val escaped = runBlocking { withTimeoutOrNull(5.seconds) { workerFailure.await() } }
                if (escaped != null) {
                    error("engine worker escaped on the closed Database: ${escaped.stackTraceToString()}")
                }
                db.connection.isClosed.shouldBeTrue()
            }
        }
    })