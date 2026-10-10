package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.engine.Event
import stt.engine.ProcessingEngine
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

class TaskDeletePendingSpec :
    BehaviorSpec({
        given("a pending QUEUED task (queue paused)") {
            `when`("deleting it") {
                then("it is cancelled through the engine first, then removed") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            TaskRepository(db, TaskWorkDirectories(dataDir)).createFromUpload(
                                queuedTask("pending.wav", createdAt = 1).copy(queuePosition = 0),
                                "pending bytes".toByteArray().inputStream(),
                            )
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        val engine: ProcessingEngine = application.dependencies.resolve()
                        var observed: List<Event> = emptyList()
                        // UNDISPATCHED: the subscription must exist before the DELETE emits anything, or the
                        // events are gone (the flow has no replay).
                        val collecting =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                observed =
                                    withTimeout(10.seconds) {
                                        engine.events
                                            .filter { it is Event.TaskUpdated || it is Event.TaskDeleted }
                                            .take(2)
                                            .toList()
                                    }
                            }

                        val response = client.delete("/api/tasks/$id")
                        response.status shouldBe HttpStatusCode.NoContent
                        response.bodyAsText().shouldBe("")

                        collecting.join()
                        observed.map { it::class } shouldBe listOf(Event.TaskUpdated::class, Event.TaskDeleted::class)
                        val canceled = observed[0] as Event.TaskUpdated
                        canceled.task.id shouldBe id
                        canceled.task.task.status shouldBe TaskStatus.CANCELED
                        (observed[1] as Event.TaskDeleted).id shouldBe id
                    }

                    Database.open(dataDir).use { db ->
                        TaskRepository(db, TaskWorkDirectories(dataDir)).find(id) shouldBe null
                    }
                }
            }
        }
    })
