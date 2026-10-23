package stt

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the app (`stt.MainKt`) as a separate JVM on [dataDir] and [port]. [appTemp] becomes its
 * `java.io.tmpdir` and [nativeTemp] the directory SQLite and Netty unpack their native libraries into, when given;
 * [env] adds or overrides variables (the caller's own are inherited either way), and the output goes to [log].
 * Integration specs use this when only a real process can show the behaviour (a kill, a closed stdin).
 */
internal fun startBackend(
    dataDir: Path,
    port: Int,
    log: Path,
    appTemp: Path? = null,
    nativeTemp: Path? = null,
    env: Map<String, String> = emptyMap(),
): Process {
    val java =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElseThrow()
    val arguments =
        buildList {
            add(java)
            if (appTemp != null) add("-Djava.io.tmpdir=$appTemp")
            if (nativeTemp != null) {
                add("-Dorg.sqlite.tmpdir=$nativeTemp")
                add("-Dio.netty.native.workdir=$nativeTemp")
            }
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("stt.MainKt")
        }
    val builder =
        ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
    builder.environment()["STT_DATA_DIR"] = dataDir.toString()
    builder.environment()["STT_PORT"] = port.toString()
    builder.environment().remove("STT_STATIC_DIR")
    builder.environment().putAll(env)
    return builder.start()
}

/** Whether the app on [port] answers `GET /api/tasks` with 200 within [timeout]. */
internal suspend fun awaitServing(
    port: Int,
    timeout: Duration = 60.seconds,
): Boolean {
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/tasks")).build()
    val deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    while (System.nanoTime() < deadline) {
        val status =
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            } catch (e: IOException) {
                // Not listening yet: the startup is what the timeout is for.
                null
            }
        if (status == 200) return true
        delay(100)
    }
    return false
}

/** A port nothing listens on, for a backend this test starts. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }
