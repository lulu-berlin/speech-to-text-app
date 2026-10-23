package stt

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

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
                    val dataDir = tempdir("stt-stdin-stop")
                    val port = ServerSocket(0).use { it.localPort }
                    val java =
                        ProcessHandle
                            .current()
                            .info()
                            .command()
                            .orElseThrow()
                    val builder =
                        ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "stt.MainKt")
                            .redirectErrorStream(true)
                            .redirectOutput(tempdir("stt-stdin-stop-log").resolve("app.log"))
                    builder.environment()["STT_DATA_DIR"] = dataDir.absolutePath
                    builder.environment()["STT_PORT"] = port.toString()
                    builder.environment()["STT_STOP_ON_STDIN_CLOSE"] = "1"
                    builder.environment().remove("STT_STATIC_DIR")
                    val app = builder.start()
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
    })

/** Whether the app on [port] answers `GET /api/tasks` with 200 within a minute. */
private suspend fun awaitServing(port: Int): Boolean {
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/tasks")).build()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (System.nanoTime() < deadline) {
        val status =
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            } catch (e: java.io.IOException) {
                null
            }
        if (status == 200) return true
        delay(100)
    }
    return false
}
