package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.log
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

/**
 * The contract's last-resort error body (item 12's Errors paragraph, acceptance "Error responses always use the
 * `{ "error", "message" }` body"): an exception that escapes every route becomes `500 internal` as JSON, with the
 * cause only logged. Without it, Ktor's default answers an unhandled exception with an empty 500. Deliberately
 * not the `StatusPages` plugin: that is a separate artifact in Ktor 3 and would need a Nix lockfile change for
 * one handler (same reasoning as ADR 0038).
 */
fun Application.internalErrorHandler() {
    intercept(ApplicationCallPipeline.Call) {
        try {
            proceed()
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            this@internalErrorHandler.log.error("Unhandled exception while serving ${context.request.path()}", cause)
            // The response may already be committed (e.g. a mid-stream SSE failure): then the clean 500 cannot
            // be written and the original failure must surface, carrying the failed write as suppressed.
            try {
                context.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Unexpected server error"))
            } catch (response: Throwable) {
                cause.addSuppressed(response)
                throw cause
            }
        }
    }
}
