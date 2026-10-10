package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
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

class AudioRangeSpec :
    BehaviorSpec({

        /** A joiner that records its calls and writes a fixed payload to the target: the route's seam. */
        class RecordingJoiner : AudioJoiner {
            val payload = ByteArray(100) { ('a'.code + it % 26).toByte() }

            override suspend fun join(
                taskId: Long,
                target: Path,
            ) {
                Files.write(target, payload)
            }
        }

        given("a task with a cached joined FLAC of 100 bytes (queue paused)") {
            `when`("requesting byte ranges of its audio") {
                then("206 is returned with the requested bytes and the right Content-Range") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val workDirectories = TaskWorkDirectories(dataDir)
                            val id =
                                TaskRepository(db, workDirectories).createFromUpload(
                                    queuedTask("memo.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "audio bytes".toByteArray().inputStream(),
                                )
                            ChunkRepository(db).storePlan(
                                id,
                                exportedChunks(dataDir, "task-$id", PlannedChunk(0L, 1_000L)),
                            )
                            id
                        }
                    val joiner = RecordingJoiner()

                    testApplication {
                        application {
                            dependencies.provide<AudioJoiner> { joiner }
                            sttApp(stt.config.testConfig(dataDir))
                        }

                        // No range: the whole file, as before.
                        val whole = client.get("/api/tasks/$id/audio")
                        whole.status shouldBe HttpStatusCode.OK
                        whole.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                        whole.bodyAsBytes() shouldBe joiner.payload

                        // Compose the expected slice from the payload itself and check headers by hand.
                        suspend fun range(
                            value: String,
                            start: Long,
                            end: Long,
                            status: HttpStatusCode = HttpStatusCode.PartialContent,
                        ) {
                            val response =
                                client.get("/api/tasks/$id/audio") {
                                    header(HttpHeaders.Range, value)
                                }
                            response.status shouldBe status
                            if (status == HttpStatusCode.PartialContent) {
                                response.headers[HttpHeaders.ContentRange] shouldBe "bytes $start-$end/${joiner.payload.size}"
                                response.bodyAsBytes() shouldBe joiner.payload.copyOfRange(start.toInt(), end.toInt() + 1)
                            }
                        }

                        range("bytes=0-3", 0, 3)
                        range("bytes=90-", 90, 99)
                        range("bytes=-8", 92, 99)
                        range("bytes=0-0", 0, 0)

                        // Past the end: 416 with a Content-Range of "*"; nothing else served.
                        val unsatisfiable =
                            client.get("/api/tasks/$id/audio") {
                                header(HttpHeaders.Range, "bytes=200-300")
                            }
                        unsatisfiable.status shouldBe HttpStatusCode.RequestedRangeNotSatisfiable
                        unsatisfiable.headers[HttpHeaders.ContentRange] shouldBe "bytes */${joiner.payload.size}"
                    }
                }
            }
        }
    })
