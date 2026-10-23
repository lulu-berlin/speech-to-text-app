package stt

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 49's review finding (decided: a graceful desktop quit on Windows). Windows has no signal the desktop
 * app can send the backend, so it closes the backend's stdin instead; the backend honours that only when the desktop
 * sets `STT_STOP_ON_STDIN_CLOSE=1`, and then stops the way a SIGTERM stops it on Linux and macOS (its engine cancels
 * the running tool, so the task is left for recovery). The app runs as a real process: only a real stdin closes.
 */
class StopOnStdinCloseIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a backend started with STT_STOP_ON_STDIN_CLOSE=1") {
            `when`("its stdin closes") {
                then("it stops") {
                    val dataDir = tempdir("stt-stdin-stop").toPath()
                    val port = freePort()
                    val app =
                        startBackend(
                            dataDir,
                            port,
                            tempdir("stt-stdin-stop-log").toPath().resolve("app.log"),
                            env = mapOf("STT_STOP_ON_STDIN_CLOSE" to "1"),
                        )
                    try {
                        awaitServing(port) shouldBe true

                        app.outputStream.close()

                        app.waitFor(30, TimeUnit.SECONDS) shouldBe true
                    } finally {
                        app.destroyForcibly().waitFor()
                    }
                }
            }
        }

        given("a backend started without STT_STOP_ON_STDIN_CLOSE, as gradle run and stt.cmd start it") {
            `when`("its stdin closes") {
                then("it keeps running and serving") {
                    val dataDir = tempdir("stt-stdin-keep").toPath()
                    val port = freePort()
                    val app =
                        startBackend(
                            dataDir,
                            port,
                            tempdir("stt-stdin-keep-log").toPath().resolve("app.log"),
                            env = mapOf("STT_STOP_ON_STDIN_CLOSE" to "0"),
                        )
                    try {
                        awaitServing(port) shouldBe true

                        app.outputStream.close()
                        delay(5.seconds)

                        app.isAlive shouldBe true
                        awaitServing(port) shouldBe true
                    } finally {
                        app.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })
