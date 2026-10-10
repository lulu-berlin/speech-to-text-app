package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import kotlinx.serialization.Serializable

/** The body of every error response (item 12's contract, `docs/api/error.json`): a code and a readable message. */
@Serializable
data class ApiError(
    val error: String,
    val message: String,
)

/**
 * The 409 of a duplicate upload (item 12's contract, `docs/api/error.duplicate-audio.json`): the only error
 * that names the task it refers to.
 */
@Serializable
data class DuplicateAudioError(
    val error: String,
    val message: String,
    val taskId: Long,
)

suspend fun RoutingCall.respondError(
    status: HttpStatusCode,
    error: String,
    message: String,
) = respond(status, ApiError(error, message))
