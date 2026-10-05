package stt

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import stt.config.Config
import stt.http.sttApp

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080) {
        sttApp(Config.fromEnv())
    }.start(wait = true)
}