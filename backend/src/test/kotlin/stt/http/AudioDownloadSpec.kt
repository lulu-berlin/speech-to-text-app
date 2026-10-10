package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.audio.AudioJoiner
import stt.audio.PlannedChunk
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.exportedChunks
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Files
import java.nio.file.Path

class AudioDownloadSpec :
    BehaviorSpec({

        /** A joiner that records its calls and writes a fixed payload to the target: the route's seam. */
        class RecordingJoiner : AudioJoiner {
            val calls = mutableListOf<Long>()
            val payload = "joined-flac-bytes".toByteArray()

            override suspend fun join(
                taskId: Long,
                target: Path,
            ) {
                calls += taskId
                Files.write(target, payload)
            }
        }

        given("a task with stored chunk FLACs (queue paused)") {
            `when`("requesting its audio twice") {
                then("the joined FLAC is served as audio/flac and built only once") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val workDirectories = TaskWorkDirectories(dataDir)
                            val tasks = TaskRepository(db, workDirectories)
                            val id =
                                tasks.createFromUpload(
                                    queuedTask("memo.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "audio bytes".toByteArray().inputStream(),
                                )
                            ChunkRepository(db).storePlan(
                                id,
                                exportedChunks(dataDir, "task-$id", PlannedChunk(0L, 1_000L), PlannedChunk(1_000L, 2_000L)),
                            )
                            id
                        }
                    val joiner = RecordingJoiner()

                    testApplication {
                        application {
                            dependencies.provide<AudioJoiner> { joiner }
                            sttApp(stt.config.testConfig(dataDir))
                        }

                        val first = client.get("/api/tasks/$id/audio")
                        first.status shouldBe HttpStatusCode.OK
                        first.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                        first.bodyAsBytes() shouldBe joiner.payload

                        val second = client.get("/api/tasks/$id/audio")
                        second.status shouldBe HttpStatusCode.OK
                        second.bodyAsBytes() shouldBe joiner.payload

                        joiner.calls shouldBe listOf(id)
                    }

                    Files.exists(dataDir.resolve("tmp").resolve("$id.flac")) shouldBe true
                }
            }
        }

        given("a task without stored chunks (queue paused)") {
            `when`("requesting its audio") {
                then("404 no-audio is returned with the error body") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            TaskRepository(db, TaskWorkDirectories(dataDir)).createFromUpload(
                                queuedTask("empty.wav", createdAt = 1).copy(queuePosition = 0),
                                "audio bytes".toByteArray().inputStream(),
                            )
                        }
                    val joiner = RecordingJoiner()

                    testApplication {
                        application {
                            dependencies.provide<AudioJoiner> { joiner }
                            sttApp(stt.config.testConfig(dataDir))
                        }

                        val response = client.get("/api/tasks/$id/audio")
                        response.status shouldBe HttpStatusCode.NotFound
                        val body = Json.parseToJsonElement(response.bodyAsBytes().toString(Charsets.UTF_8)).jsonObject
                        body shouldHaveShapeOf apiExample("error.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "no-audio"
                        joiner.calls.shouldBeEmpty()
                    }
                }
            }
        }
    })
