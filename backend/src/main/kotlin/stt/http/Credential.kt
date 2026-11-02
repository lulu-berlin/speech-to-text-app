package stt.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.security.MessageDigest

/** What a refused request answers in app mode (roadmap item 66; `docs/api/error.unauthorized.json`). */
internal val UNAUTHORIZED_ERROR = ApiError("unauthorized", "This request needs the service's credential")

/** What a request from another site, or one that did not address this machine, answers (`docs/api/error.forbidden.json`). */
internal val FORBIDDEN_ORIGIN_ERROR = ApiError("forbidden-origin", "This service answers this machine's app only")

/** The names a request from this machine may use to address the service. */
private val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost", "[::1]", "::1")

/**
 * Roadmap item 66: in app mode a request must carry the per-install [credential] as `Authorization: Bearer …` — how the
 * TUI and the tray send it — or the session cookie the launch handshake set (see [launchRoutes]). Any other request is
 * refused with 401 `unauthorized`; [OPEN_PATH] is left to the handshake, which validates its own one-time token. The
 * secret comparison takes the same time whatever the request sent, so a guess learns nothing from the timing.
 */
internal fun Application.requireCredential(
    credential: String,
    access: BrowserAccess,
) {
    install(
        createApplicationPlugin("RequireCredential") {
            onCall { call ->
                when {
                    !call.isTrustedOrigin() -> call.respond(HttpStatusCode.Forbidden, FORBIDDEN_ORIGIN_ERROR)
                    call.request.path() == OPEN_PATH -> Unit
                    call.authorized(credential, access) -> Unit
                    else -> call.refuse()
                }
            }
        },
    )
}

/**
 * Refuses the call: a browser navigating to a page gets [BROWSER_REFUSAL_PAGE] (roadmap item 66, D6), because the bare
 * `401` JSON is no instruction to a person; everything else - the API, the frontend's own fetches - keeps the
 * contract's [UNAUTHORIZED_ERROR].
 */
private suspend fun ApplicationCall.refuse() {
    val navigating =
        !request.path().startsWith("/api") &&
            request.header(HttpHeaders.Accept)?.contains("text/html", ignoreCase = true) == true
    if (navigating) {
        respondText(BROWSER_REFUSAL_PAGE, ContentType.Text.Html, HttpStatusCode.Unauthorized)
    } else {
        respond(HttpStatusCode.Unauthorized, UNAUTHORIZED_ERROR)
    }
}

/**
 * Whether [this] call addressed the service by a loopback name and, when it says where it came from, came from that same
 * address. A DNS-rebinding attack makes the browser send the attacker's host; another site on this machine sends its own
 * `Origin`. See [LOOPBACK_NAMES].
 */
private fun ApplicationCall.isTrustedOrigin(): Boolean {
    val host = request.headers[HttpHeaders.Host]?.lowercase() ?: return false
    val hostName = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
    if (hostName !in LOOPBACK_NAMES) return false
    val origin = request.headers[HttpHeaders.Origin]?.lowercase() ?: return true
    return origin == "http://$host"
}

/** Whether [authorization] is the [credential] itself as `Authorization: Bearer …`, in the same time whatever was sent. */
internal fun carriesSecret(
    authorization: String?,
    credential: String,
): Boolean {
    val sent = authorization?.toByteArray() ?: return false
    return MessageDigest.isEqual(sent, "Bearer $credential".toByteArray())
}

/** Whether [this] call sent the [credential] itself or a live session of this process. */
private suspend fun ApplicationCall.authorized(
    credential: String,
    access: BrowserAccess,
): Boolean {
    if (carriesSecret(request.header(HttpHeaders.Authorization), credential)) return true
    val session = request.cookies[SESSION_COOKIE] ?: return false
    return access.isSession(session)
}
