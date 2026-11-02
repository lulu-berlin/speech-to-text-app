package stt

import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.config.Config
import stt.http.sttApp
import stt.process.ProcessRunner
import stt.service.ServiceDirs
import stt.service.ServiceInstance
import stt.service.TraySupervisor
import stt.service.startOnDurablePort
import stt.transcribe.DeviceProbe
import stt.transcribe.DeviceProbeOutcome
import stt.transcribe.GpuDevice
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val bootLogger: Logger = LoggerFactory.getLogger("stt.Main")

/** The program argument that starts the app mode, the per-user service (roadmap item 66); without it the app runs in dev/server mode. */
private const val SERVICE_MODE = "service"

/**
 * Where app mode listens (roadmap item 66): this machine's loopback only, whatever `STT_HOST` says — dev/server mode keeps
 * `STT_HOST`. The clients are on this machine; nothing on the LAN may reach the service.
 */
private const val SERVICE_HOST = "127.0.0.1"

/**
 * The service's default port (roadmap item 66, settled 2026-10-29): unassigned at IANA (28590-28999) and below the
 * ephemeral range of every OS, so no outgoing connection takes it by chance. `STT_PORT` seeds another first port.
 */
private const val DEFAULT_SERVICE_PORT = 28763

fun main(args: Array<String>) {
    val config = Config.fromEnv()
    if (args.firstOrNull() == SERVICE_MODE) runService(config) else createServer(config, probed = probeGpus(config)).start(wait = true)
}

/**
 * The app mode (roadmap item 66): the process takes the user's service instance and serves on its durable port, or on
 * a free port when another program holds that one, advertising itself in `runtime.json` once it answers. When another
 * process already holds the instance, this launch defers to it and ends without starting a server.
 */
private fun runService(config: Config) {
    val firstPort = if (config.portConfigured) config.port else DEFAULT_SERVICE_PORT
    val dirs = ServiceDirs.fromEnv()
    val instance = runBlocking { ServiceInstance.claim(dirs, firstPort) }
    if (instance == null) {
        bootLogger.info("The service already runs for this user; not starting another")
        return
    }
    instance.use {
        // The check spares the common case - the port has been taken for a while - a start of the whole app that the
        // bind then refuses; the bind's own fallback covers a program taking the port after the check (D5).
        val port = runBlocking { instance.portToServe(SERVICE_HOST) }
        // Probed once: a bind that falls back builds a second server, and whisper-cli runs once either way.
        val probed = probeGpus(config)
        // `POST /api/quit` completes this, and the watcher stops the server: the same clean stop a SIGTERM performs,
        // which withdraws `runtime.json` and cancels the engine's running tool. The fallback bind replaces the server,
        // so the watcher reads it from the reference.
        val quit = CompletableDeferred<Unit>()
        // The unit's other half (ADR 0064): the tray registers itself, and a tray that died is started again through
        // the command it registered; when it cannot be, the service stops too, through the same clean-stop path.
        val traySupervisor =
            TraySupervisor(
                startTray = { command -> ProcessBuilder(command).start() },
                alive = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
                now = { Instant.now() },
                stopService = { reason ->
                    bootLogger.warn("Tray supervision stopped the service: {}", reason)
                    quit.complete(Unit)
                },
            )
        val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)
        // One scope for the service's lifetime (AGENTS.md, coroutines): the quit watcher and the tray's
        // supervision clock. It is cancelled when the server stops, so the fallback bind, which builds a
        // second server, does not leave a second clock behind.
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope.launch {
            quit.await()
            serverRef.get()?.stop()
        }
        serviceScope.launch {
            while (isActive) {
                delay(TRAY_LOOK_INTERVAL_MS)
                traySupervisor.check()
            }
        }
        startOnDurablePort(port, wait = true) { portToBind ->
            val server =
                createServer(
                    config.copy(port = portToBind, host = SERVICE_HOST),
                    credential = instance.config.secret,
                    frontendsDir = dirs.configDir.resolve("frontends"),
                    probed = probed,
                    quit = quit,
                    registerTray = traySupervisor::registered,
                )
            serverRef.set(server)
            server.monitor.subscribe(ServerReady) {
                runBlocking {
                    val actualPort =
                        server.engine
                            .resolvedConnectors()
                            .first()
                            .port
                    instance.advertise(ProcessHandle.current().pid(), actualPort)
                }
            }
            // The stop's first event: the shutdown hook of a SIGTERM (a logout, `stt-app quit`) runs it too, where
            // the code after `start` would not get to run.
            server.monitor.subscribe(ApplicationStopPreparing) {
                serviceScope.cancel()
                runBlocking { instance.withdraw() }
            }
            server
        }
    }
}

/** The GPUs the startup probe reported, and why it failed, if it did. */
private data class ProbedGpus(
    val gpus: List<GpuDevice>,
    val error: String?,
)

/**
 * The startup device probe (item 32): whisper-cli is asked once which GPUs it can use, and the Settings page offers
 * them. [createServer] runs it for the dev/server mode's single server; the app mode probes once for both attempts.
 */
private fun probeGpus(config: Config): ProbedGpus {
    val probe = runBlocking { DeviceProbe(ProcessRunner(), config.whisperModel).probe() }
    val probed =
        when (probe) {
            is DeviceProbeOutcome.Probed -> ProbedGpus(probe.gpus, null)
            is DeviceProbeOutcome.LaunchFailed -> ProbedGpus(emptyList(), probe.message)
        }
    if (probed.error == null) {
        bootLogger.info("Device probe: {} GPU(s) {}", probed.gpus.size, probed.gpus)
    } else {
        bootLogger.warn("Device probe failed: {}; offering CPU and auto", probed.error)
    }
    return probed
}

/**
 * The app's server per [config], not yet started, with the startup probe's result and the stdin stop wired.
 * [credential] is the service's secret in app mode, which every request must then carry; null in dev/server mode.
 */
private fun createServer(
    config: Config,
    credential: String? = null,
    frontendsDir: Path? = null,
    probed: ProbedGpus = probeGpus(config),
    quit: CompletableDeferred<Unit>? = null,
    registerTray: ((pid: Long, command: List<String>) -> Unit)? = null,
): EmbeddedServer<*, *> {
    warnIfStaticDirUnset(config, bootLogger)
    // The host is part of the configuration (desktop item 50: 127.0.0.1 when STT_HOST says
    // so; every other path keeps the default 0.0.0.0).
    val server =
        embeddedServer(Netty, host = config.host, port = config.port) {
            sttApp(config, probed.gpus, probed.error, credential, frontendsDir, quit, registerTray)
        }
    if (config.stopOnStdinClose) stopWhenClosed(System.`in`, server)
    return server
}

/**
 * Stops [server] once [input] ends (roadmap item 49): the desktop app closes the backend's stdin to quit it gracefully
 * on Windows, where it has no signal to send. The stop is the one a SIGTERM's shutdown hook performs, so the engine
 * cancels its running tool and the task is left for recovery. The read blocks, so it runs on the IO dispatcher in a
 * scope of its own; its threads are daemons and never keep the JVM alive.
 */
private fun stopWhenClosed(
    input: InputStream,
    server: EmbeddedServer<*, *>,
) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            while (input.read() >= 0) {
                // Nothing is sent on stdin: only its end matters.
            }
        } catch (e: IOException) {
            bootLogger.info("stdin failed; stopping as if it had closed", e)
        }
        bootLogger.info("stdin closed; stopping")
        server.stop()
    }
}

/**
 * Logs a warning when static serving is disabled because STT_STATIC_DIR is unset or empty,
 * so a started server without a UI explains itself (spec: startup logs a warning).
 */
internal fun warnIfStaticDirUnset(
    config: Config,
    logger: Logger = bootLogger,
) {
    if (config.staticDir == null) {
        logger.warn("STT_STATIC_DIR is unset; static file serving is disabled")
    }
}

/** How often the service looks at its registered tray (ADR 0064). */
private const val TRAY_LOOK_INTERVAL_MS = 1_000L
