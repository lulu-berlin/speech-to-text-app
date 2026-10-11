package stt

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.config.Config
import stt.http.sttApp
import stt.process.ProcessRunner
import stt.transcribe.DeviceProbe

private val bootLogger: Logger = LoggerFactory.getLogger("stt.Main")

fun main() {
    val config = Config.fromEnv()
    warnIfStaticDirUnset(config, bootLogger)
    // The startup device probe (item 32): whisper-cli is asked once which GPUs it can use; the Settings page offers them.
    val gpus = runBlocking { DeviceProbe(ProcessRunner(), config.whisperModel).gpus() }
    bootLogger.info("Device probe: {} GPU(s) {}", gpus.size, gpus)
    embeddedServer(Netty, port = config.port) {
        sttApp(config, gpus)
    }.start(wait = true)
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
