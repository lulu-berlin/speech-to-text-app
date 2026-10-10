package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.engine.ProcessingEngine

/** The queue of item 12's contract (`docs/api/queue.json`): the paused flag and the pending task ids in queue order. */
@Serializable
data class QueueJson(
    val paused: Boolean,
    val taskIds: List<Long>,
)

/** The body of `PUT /api/queue/order` (item 12's contract). */
@Serializable
data class QueueOrderRequest(
    val taskIds: List<Long>,
)

/** The queue endpoints (item 12): read it, pause/resume it, or write a new order (delegating to the engine). */
fun Application.queueRoutes() {
    val engine: ProcessingEngine by dependencies
    val tasks: TaskRepository by dependencies
    val queueState: QueueStateRepository by dependencies
    routing {
        get("/api/queue") {
            call.respondQueue(engine, tasks, queueState)
        }

        post("/api/queue/pause") {
            engine.pauseQueue()
            call.respondQueue(engine, tasks, queueState)
        }

        post("/api/queue/resume") {
            engine.resumeQueue()
            call.respondQueue(engine, tasks, queueState)
        }

        put("/api/queue/order") {
            val ids = call.receive<QueueOrderRequest>().taskIds
            // The contract: the body must carry exactly the pending task ids, in any order; anything else is
            // a mismatch and nothing changes.
            if (ids.toSet() != tasks.pendingOrderedIds().toSet()) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "reorder-mismatch",
                    "taskIds must be exactly the pending tasks (${tasks.pendingOrderedIds()})",
                )
            } else {
                engine.reorderQueue(ids)
                call.respondQueue(engine, tasks, queueState)
            }
        }
    }
}

private suspend fun RoutingCall.respondQueue(
    engine: ProcessingEngine,
    tasks: TaskRepository,
    queueState: QueueStateRepository,
) {
    respond(QueueJson(paused = queueState.isPaused(), taskIds = tasks.pendingOrderedIds()))
}
