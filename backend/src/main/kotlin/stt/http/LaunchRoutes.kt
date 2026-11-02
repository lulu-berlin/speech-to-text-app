package stt.http

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** The body of `POST /api/launch-token` (roadmap item 66). */
@Serializable
internal data class LaunchToken(
    val token: String,
)

/**
 * The browser's way into app mode (roadmap item 66). The tray and `stt-app open` ask for a single-use token with the
 * per-install credential as `Authorization: Bearer …`, then open [OPEN_PATH]`?t=<token>` in the OS browser: the service
 * starts a session, answers 303 to the app with the session id in an `HttpOnly`, `SameSite=Strict` cookie, and the
 * app's own requests and event stream are served with that cookie. The secret never reaches the browser.
 */
internal fun Application.launchRoutes(
    credential: String,
    access: BrowserAccess,
) {
    routing {
        post("/api/launch-token") {
            if (!carriesSecret(call.request.header(HttpHeaders.Authorization), credential)) {
                return@post call.respond(HttpStatusCode.Unauthorized, UNAUTHORIZED_ERROR)
            }
            call.respond(LaunchToken(access.newToken()))
        }
        get(OPEN_PATH) {
            val session = call.request.queryParameters["t"]?.let { access.redeem(it) }
            if (session == null) {
                return@get call.respond(HttpStatusCode.Unauthorized, UNAUTHORIZED_ERROR)
            }
            call.response.cookies.append(
                Cookie(SESSION_COOKIE, session, path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Strict")),
            )
            call.response.header(HttpHeaders.Location, "/")
            call.respond(HttpStatusCode.SeeOther)
        }
    }
}
