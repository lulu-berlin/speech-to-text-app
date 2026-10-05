package stt.http

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
)

fun Application.healthRoutes() {
    routing {
        get("/api/health") {
            call.respond(HealthResponse("ok"))
        }
    }
}
