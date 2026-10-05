package stt

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import stt.config.Config
import stt.http.sttApp

fun main(args: Array<String>) {
    val config = Config.fromEnv()
    embeddedServer(Netty, port = config.port) {
        sttApp(config)
    }.start(wait = true)
}
