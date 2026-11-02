package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred

/**
 * Roadmap item 66, item 4: quitting the service is explicit. `POST /api/quit` is answered `202 Accepted` and then
 * completes [quit], which the service's start is waiting on to stop the server - the clean stop that also removes
 * `runtime.json` (D3). Registered in app mode only, and behind the credential like every other route there.
 */
fun Application.quitRoutes(quit: CompletableDeferred<Unit>) {
    routing {
        post("/api/quit") {
            call.respond(HttpStatusCode.Accepted)
            quit.complete(Unit)
        }
    }
}
