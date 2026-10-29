package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
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
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.Task
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Path

class TurnsSpec :
    BehaviorSpec({

        suspend fun seed(
            dataDir: Path,
            task: Task,
            segments: List<Segment>,
            speakerIds: List<Int>?,
        ): Long =
            Database.open(dataDir).use { db ->
                QueueStateRepository(db).setPaused(true)
                val id =
                    TaskRepository(db)
                        .createFromUpload(task, "${task.originalFilename} bytes".toByteArray().inputStream())
                SegmentRepository(db).apply {
                    insertAll(id, segments)
                    if (speakerIds != null) storeSpeakers(id, speakerIds)
                }
                id
            }

        fun completed(
            name: String,
            speakersMode: SpeakersMode = SpeakersMode.AUTO,
        ) = queuedTask(name, createdAt = 1).copy(
            status = TaskStatus.COMPLETED,
            finishedAt = 1_000,
            speakersMode = speakersMode,
        )

        given("a COMPLETED task with stored speakers (queue paused)") {
            `when`("requesting its turns") {
                then("the speakers with their stored names and the turns with their segments are returned") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        seed(
                            dataDir,
                            completed("memo.wav"),
                            listOf(
                                Segment(0, 0, 0, 1_500, " Hello there.", speakerId = null),
                                Segment(0, 1, 1_500, 3_250, " again.", speakerId = null),
                                Segment(1, 0, 3_250, 4_000, " Fine.", speakerId = null),
                            ),
                            speakerIds = listOf(0, 0, 1),
                        )

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.get("/api/tasks/$id/turns")
                        response.status shouldBe HttpStatusCode.OK
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("turns.json")
                        // Derived by hand from the contract: consecutive same-speakerId segments form one turn
                        // (first start, last end), texts trimmed, turn text joined with single spaces.
                        body shouldBe
                            Json.parseToJsonElement(
                                """
                                {
                                  "hasMerges": false,
                                  "speakers": [
                                    { "speakerId": 0, "speaker": "Speaker 1" },
                                    { "speakerId": 1, "speaker": "Speaker 2" }
                                  ],
                                  "turns": [
                                    {
                                      "speakerId": 0, "startMs": 0, "endMs": 3250, "text": "Hello there. again.",
                                      "segments": [
                                        { "id": 1, "startMs": 0, "endMs": 1500, "text": "Hello there.", "edited": false },
                                        { "id": 2, "startMs": 1500, "endMs": 3250, "text": "again.", "edited": false }
                                      ]
                                    },
                                    {
                                      "speakerId": 1, "startMs": 3250, "endMs": 4000, "text": "Fine.",
                                      "segments": [ { "id": 3, "startMs": 3250, "endMs": 4000, "text": "Fine.", "edited": false } ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                    }
                }
            }
        }

        given("a COMPLETED speakers-off task (queue paused)") {
            `when`("requesting its turns") {
                then("there are no speakers and one turn per segment with speakerId null") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        seed(
                            dataDir,
                            completed("off.wav", SpeakersMode.OFF),
                            listOf(
                                Segment(0, 0, 0, 1_000, " One.", speakerId = null),
                                Segment(0, 1, 1_000, 2_000, " Two.", speakerId = null),
                            ),
                            speakerIds = null,
                        )

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.get("/api/tasks/$id/turns")
                        response.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("turns.speakers-off.json")
                        body shouldBe
                            Json.parseToJsonElement(
                                """
                                {
                                  "hasMerges": false,
                                  "speakers": [],
                                  "turns": [
                                    {
                                      "speakerId": null, "startMs": 0, "endMs": 1000, "text": "One.",
                                      "segments": [ { "id": 1, "startMs": 0, "endMs": 1000, "text": "One.", "edited": false } ]
                                    },
                                    {
                                      "speakerId": null, "startMs": 1000, "endMs": 2000, "text": "Two.",
                                      "segments": [ { "id": 2, "startMs": 1000, "endMs": 2000, "text": "Two.", "edited": false } ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                    }
                }
            }
        }

        given("a COMPLETED task whose segment texts carry surrounding spaces and a blank segment (queue paused)") {
            `when`("requesting its turns") {
                then("segment texts are trimmed and the turn text joins the non-blank trimmed texts with single spaces") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        seed(
                            dataDir,
                            completed("spaces.wav"),
                            listOf(
                                Segment(0, 0, 0, 1_000, "  Hi  ", speakerId = null),
                                Segment(0, 1, 1_000, 2_000, "   ", speakerId = null),
                                Segment(0, 2, 2_000, 3_000, " there ", speakerId = null),
                            ),
                            speakerIds = listOf(0, 0, 0),
                        )

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.get("/api/tasks/$id/turns")
                        response.status shouldBe HttpStatusCode.OK
                        // Hand trace of item 09's turns: "Hi"; f"Hi ".strip() = "Hi";
                        // f"Hi there".strip() = "Hi there" — the blank segment adds no second space.
                        Json.parseToJsonElement(response.bodyAsText()) shouldBe
                            Json.parseToJsonElement(
                                """
                                {
                                  "hasMerges": false,
                                  "speakers": [ { "speakerId": 0, "speaker": "Speaker 1" } ],
                                  "turns": [
                                    {
                                      "speakerId": 0, "startMs": 0, "endMs": 3000, "text": "Hi there",
                                      "segments": [
                                        { "id": 1, "startMs": 0, "endMs": 1000, "text": "Hi", "edited": false },
                                        { "id": 2, "startMs": 1000, "endMs": 2000, "text": "", "edited": false },
                                        { "id": 3, "startMs": 2000, "endMs": 3000, "text": "there", "edited": false }
                                      ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                    }
                }
            }
        }

        given("a QUEUED task (queue paused)") {
            `when`("requesting its turns") {
                then("409 not-completed is returned with the error body") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, queuedTask("todo.wav", createdAt = 1).copy(queuePosition = 0), emptyList(), null)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.get("/api/tasks/$id/turns")
                        response.status shouldBe HttpStatusCode.Conflict
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("error.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "not-completed"
                    }
                }
            }
        }
    })
