package stt

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.config.Config
import stt.http.sttApp
import stt.process.ProcessRunner
import stt.transcribe.DeviceProbe
import stt.transcribe.DeviceProbeOutcome
import stt.transcribe.GpuDevice
import java.io.IOException
import java.io.InputStream

private val bootLogger: Logger = LoggerFactory.getLogger("stt.Main")

fun main() {
    val config = Config.fromEnv()
    warnIfStaticDirUnset(config, bootLogger)
    // The startup device probe (item 32): whisper-cli is asked once which GPUs it can use; the Settings page offers them.
    val probe = runBlocking { DeviceProbe(ProcessRunner(), config.whisperModel).probe() }
    val (gpus, deviceProbeError) =
        when (probe) {
            is DeviceProbeOutcome.Probed -> probe.gpus to null
            is DeviceProbeOutcome.LaunchFailed -> emptyList<GpuDevice>() to probe.message
        }
    if (deviceProbeError == null) {
        bootLogger.info("Device probe: {} GPU(s) {}", gpus.size, gpus)
    } else {
        bootLogger.warn("Device probe failed: {}; offering CPU and auto", deviceProbeError)
    }
    // The host is part of the configuration (desktop item 50: 127.0.0.1 when STT_HOST says
    // so; every other path keeps the default 0.0.0.0).
    val server =
        embeddedServer(Netty, host = config.host, port = config.port) {
            sttApp(config, gpus, deviceProbeError)
        }
    if (config.stopOnStdinClose) stopWhenClosed(System.`in`, server)
    server.start(wait = true)
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
