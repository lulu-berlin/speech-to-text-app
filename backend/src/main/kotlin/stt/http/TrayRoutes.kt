package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** The body of `POST /api/tray` (ADR 0064): the tray's pid and the command that starts it again. */
@Serializable
data class TrayJson(
    val pid: Long,
    val command: List<String>,
)

/**
 * The tray registers itself (ADR 0064), behind the credential like every other app-mode route: the service then
 * watches the tray's pid and starts its command again when the tray died, so the unit stays both up or both down.
 */
fun Application.trayRoutes(register: (pid: Long, command: List<String>) -> Unit) {
    routing {
        post("/api/tray") {
            val tray = call.receive<TrayJson>()
            register(tray.pid, tray.command)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
