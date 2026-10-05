package stt

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.config.Config
import stt.http.sttApp

private val bootLogger: Logger = LoggerFactory.getLogger("stt.Main")

fun main(args: Array<String>) {
    val config = Config.fromEnv()
    warnIfStaticDirUnset(config, bootLogger)
    embeddedServer(Netty, port = config.port) {
        sttApp(config)
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
