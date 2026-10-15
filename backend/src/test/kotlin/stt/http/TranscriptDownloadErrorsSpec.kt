package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.queue.TaskStatus

class TranscriptDownloadErrorsSpec :
    BehaviorSpec({
        given("a COMPLETED, a QUEUED and a FAILED task (queue paused)") {
            `when`("downloading with an unknown or missing format, or the transcript of an unfinished task") {
                then("400 invalid-transcript-format and 409 not-completed are returned with the error body") {
                    val dataDir = tempdir("stt-data").toPath()
                    val (completed, queued, failed) =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val tasks = TaskRepository(db)
                            listOf(
                                TaskStatus.COMPLETED to "done.wav",
                                TaskStatus.QUEUED to "todo.wav",
                                TaskStatus.FAILED to "broken.wav",
                            ).mapIndexed { index, (status, name) ->
                                tasks.createFromUpload(
                                    queuedTask(name, createdAt = index.toLong() + 1).copy(
                                        queuePosition = if (status == TaskStatus.QUEUED) 0 else null,
                                        status = status,
                                        finishedAt = if (status == TaskStatus.QUEUED) null else 1_000,
                                    ),
                                    "$name bytes".toByteArray().inputStream(),
                                )
                            }
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        suspend fun HttpResponse.shouldBeError(
                            status: HttpStatusCode,
                            error: String,
                        ) {
                            this.status shouldBe status
                            contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                            val body = Json.parseToJsonElement(bodyAsText()).jsonObject
                            body shouldHaveShapeOf apiExample("error.json")
                            body.getValue("error").jsonPrimitive.content shouldBe error
                        }

                        // json was dropped (ADR 0035); a missing format is no format either.
                        client
                            .get("/api/tasks/$completed/transcript?format=json")
                            .shouldBeError(HttpStatusCode.BadRequest, "invalid-transcript-format")
                        client
                            .get("/api/tasks/$completed/transcript")
                            .shouldBeError(HttpStatusCode.BadRequest, "invalid-transcript-format")

                        // A valid format does not help a task that is not COMPLETED.
                        client
                            .get("/api/tasks/$queued/transcript?format=txt")
                            .shouldBeError(HttpStatusCode.Conflict, "not-completed")
                        client
                            .get("/api/tasks/$failed/transcript?format=srt")
                            .shouldBeError(HttpStatusCode.Conflict, "not-completed")
                    }
                }
            }
        }
    })
