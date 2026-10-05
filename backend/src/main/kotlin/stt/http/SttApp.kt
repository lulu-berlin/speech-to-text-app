package stt.http

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.routing.routing
import stt.config.Config
import java.io.File

/**
 * Application composition (ADR 0013): plugins are installed here, feature modules
 * are loaded. The service graph grows here as later items add their modules.
 */
fun Application.sttApp(config: Config) {
    install(ContentNegotiation) { json() }
    healthRoutes()
    val staticDir = config.staticDir
    routing {
        if (staticDir != null) {
            staticFiles("/", staticDir.toFile()) {
                // /api is API-only: the SPA fallback must never serve index.html there (cycle 4).
                filter { call -> call.request.path().startsWith("/api") }
                fallback { _, call ->
                    call.respondFile(File(staticDir.toFile(), "index.html"))
                }
            }
        }
    }
}
