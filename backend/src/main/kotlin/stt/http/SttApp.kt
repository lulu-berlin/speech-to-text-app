package stt.http

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/**
 * Application composition (ADR 0013): plugins are installed here, feature modules
 * are loaded. The service graph grows here as later items add their modules.
 */
fun Application.sttApp() {
    install(ContentNegotiation) { json() }
    healthRoutes()
}