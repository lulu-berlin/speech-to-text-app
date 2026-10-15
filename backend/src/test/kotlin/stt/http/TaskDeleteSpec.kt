package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import stt.audio.PlannedChunk
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.db.storePlan
import stt.queue.TaskStatus

class TaskDeleteSpec :
    BehaviorSpec({
        given("a finished task holding segments and chunk audio") {
            `when`("deleting it") {
                then("204 is returned, everything is gone, and the same file can be uploaded again") {
                    val bytes = "THE DELETE TEST AUDIO".repeat(10).toByteArray()
                    val dataDir = tempdir("stt-data").toPath()
                    var id = 0L
                    Database.open(dataDir).use { db ->
                        QueueStateRepository(db).setPaused(true)
                        val tasks = TaskRepository(db)
                        id =
                            tasks.createFromUpload(
                                queuedTask("doomed.wav", createdAt = 1).copy(
                                    queuePosition = 0,
                                    status = TaskStatus.COMPLETED,
                                    finishedAt = 1_000,
                                ),
                                bytes.inputStream(),
                            )
                        storePlan(db, id, listOf(PlannedChunk(0L, 1_000L), PlannedChunk(1_000L, 2_000L)))
                        SegmentRepository(db).insertAll(
                            id,
                            listOf(
                                Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 500, text = "hello", speakerId = null),
                                Segment(chunkIdx = 1, seq = 0, startMs = 1_000, endMs = 1_500, text = "world", speakerId = null),
                            ),
                        )
                        id
                    }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.delete("/api/tasks/$id")
                        response.status shouldBe HttpStatusCode.NoContent
                        response.bodyAsText().shouldBe("")
                    }

                    Database.open(dataDir).use { db ->
                        TaskRepository(db).find(id).shouldBeNull()
                        SegmentRepository(db).findByTask(id).shouldBeEmpty()
                        ChunkRepository(db).findByTask(id).shouldBeEmpty()
                    }

                    // The same bytes are no longer a duplicate: the `audio` row went with the task.
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val upload: HttpResponse =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData =
                                    formData {
                                        append(
                                            "file",
                                            bytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "audio/x-wav")
                                                append(HttpHeaders.ContentDisposition, "filename=\"doomed.wav\"")
                                            },
                                        )
                                    },
                            )
                        upload.status shouldBe HttpStatusCode.Created
                    }
                }
            }
        }
    })
