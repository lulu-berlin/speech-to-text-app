package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import stt.db.TaskRepository

fun Application.taskRoutes() {
    val tasks: TaskRepository by dependencies
    routing {
        get("/api/tasks/{id}") {
            val details = call.parameters["id"]?.toLongOrNull()?.let { tasks.details(it) }
            if (details == null) {
                call.respondError(HttpStatusCode.NotFound, "not-found", "Task ${call.parameters["id"]} does not exist")
            } else {
                call.respond(details.toJson())
            }
        }
    }
}
