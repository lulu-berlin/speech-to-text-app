package stt

import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive
import stt.service.ServiceDirs
import stt.service.configFile
import stt.service.readConfig
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the app (`stt.MainKt`) as a separate JVM on [dataDir] and [port]. [appTemp] becomes its
 * `java.io.tmpdir` and [nativeTemp] the directory SQLite and Netty unpack their native libraries into, when given;
 * [env] adds or overrides variables (the caller's own are inherited either way), [args] are the program's arguments,
 * and the output goes to [log].
 * Integration specs use this when only a real process can show the behaviour (a kill, a closed stdin).
 */
internal fun startBackend(
    dataDir: Path,
    port: Int?,
    log: Path,
    appTemp: Path? = null,
    nativeTemp: Path? = null,
    env: Map<String, String> = emptyMap(),
    args: List<String> = emptyList(),
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
            addAll(args)
        }
    val builder =
        ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
    builder.environment()["STT_DATA_DIR"] = dataDir.toString()
    if (port != null) builder.environment()["STT_PORT"] = port.toString()
    builder.environment().remove("STT_STATIC_DIR")
    builder.environment().putAll(env)
    return builder.start()
}

/** Starts the app in app mode, the per-user service (roadmap item 66), with [dirs] as its config and runtime directories. */
internal fun startService(
    dataDir: Path,
    port: Int?,
    log: Path,
    dirs: ServiceDirs,
    env: Map<String, String> = emptyMap(),
): Process =
    startBackend(
        dataDir,
        port,
        log,
        env =
            mapOf(
                "STT_CONFIG_DIR" to dirs.configDir.toString(),
                "STT_RUNTIME_DIR" to dirs.runtimeDir.toString(),
            ) + env,
        args = listOf("service"),
    )

/**
 * Whether the app on [port] answers `GET /api/tasks` with 200 within [timeout]; [authorization] is asked for the
 * `Authorization` header on every attempt (null sends none).
 */
internal suspend fun awaitServing(
    port: Int,
    timeout: Duration = 60.seconds,
    authorization: () -> String? = { null },
): Boolean {
    val client = HttpClient.newHttpClient()
    val deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    while (System.nanoTime() < deadline) {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/tasks"))
        authorization()?.let { request.header("Authorization", it) }
        val status =
            try {
                client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
            } catch (e: IOException) {
                // Not listening yet: the startup is what the timeout is for.
                null
            }
        if (status == 200) return true
        delay(100)
    }
    return false
}

/**
 * Whether the service on [port] answers within [timeout] a client that sends the credential from [dirs]' `config.json`
 * (roadmap item 66), as the TUI and the tray do.
 */
internal suspend fun awaitServiceServing(
    port: Int,
    dirs: ServiceDirs,
    timeout: Duration = 60.seconds,
): Boolean =
    awaitServing(port, timeout) {
        configFile(dirs)
            .takeIf { it.exists() }
            ?.let { "Bearer " + readConfig(dirs).getValue("secret").jsonPrimitive.content }
    }

/** A port nothing listens on, for a backend this test starts. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }
