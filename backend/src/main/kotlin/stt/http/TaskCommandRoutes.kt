package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import stt.db.TaskRepository
import stt.engine.ProcessingEngine
import stt.engine.TaskCommandOutcome

/**
 * The task command endpoints (item 12): each POST delegates to the engine's command (item 11), which checks
 * the transition table, and maps the outcome onto the contract: `Accepted` → `200` with the updated task,
 * `Unknown` → `404 not-found`, `Illegal` → `409 illegal-transition`.
 */
fun Application.taskCommandRoutes() {
    val engine: ProcessingEngine by dependencies
    val tasks: TaskRepository by dependencies
    routing {
        delete("/api/tasks/{id}") {
            val id = call.taskIdOrNotFound() ?: return@delete
            when (engine.deleteTask(id)) {
                is TaskCommandOutcome.Accepted -> {
                    call.respond(HttpStatusCode.NoContent)
                }

                else -> {
                    call.respondError(
                        HttpStatusCode.NotFound,
                        "not-found",
                        "Task ${call.parameters["id"]} does not exist",
                    )
                }
            }
        }
        post("/api/tasks/{id}/pause") {
            val id = call.taskIdOrNotFound() ?: return@post
            call.respondTaskCommand(engine.pauseTask(id), tasks)
        }
        post("/api/tasks/{id}/resume") {
            val id = call.taskIdOrNotFound() ?: return@post
            call.respondTaskCommand(engine.resumeTask(id), tasks)
        }
        post("/api/tasks/{id}/cancel") {
            val id = call.taskIdOrNotFound() ?: return@post
            call.respondTaskCommand(engine.cancelTask(id), tasks)
        }
        post("/api/tasks/{id}/retry") {
            val id = call.taskIdOrNotFound() ?: return@post
            call.respondTaskCommand(engine.retryTask(id), tasks)
        }
    }
}

/** The route's `{id}` as a Long, or — when it is missing or not a number — a `404 not-found` response and null. */
private suspend fun RoutingCall.taskIdOrNotFound(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respondError(
            HttpStatusCode.NotFound,
            "not-found",
            "Task ${parameters["id"]} does not exist",
        )
    }
    return id
}

private suspend fun RoutingCall.respondTaskCommand(
    outcome: TaskCommandOutcome,
    tasks: TaskRepository,
) {
    when (outcome) {
        is TaskCommandOutcome.Accepted -> {
            val id = requireNotNull(parameters["id"]?.toLongOrNull())
            respond(HttpStatusCode.OK, tasks.details(id)!!.toJson())
        }

        is TaskCommandOutcome.Unknown -> {
            respondError(
                HttpStatusCode.NotFound,
                "not-found",
                "Task ${parameters["id"]} does not exist",
            )
        }

        is TaskCommandOutcome.Illegal -> {
            respondError(
                HttpStatusCode.Conflict,
                "illegal-transition",
                "Cannot change task ${parameters["id"]} from ${outcome.from} to ${outcome.to}",
            )
        }
    }
}
