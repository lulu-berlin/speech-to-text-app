package stt.http

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.Task
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Path

class SpeakerRoutesSpec :
    BehaviorSpec({

        given("a COMPLETED task with speakers 0 and 1 (queue paused)") {
            `when`("speaker 1 is renamed with invalid names, a 40-character name and a name with an inner space") {
                then("an untrimmed, empty, over-long or control-character name answers 400 invalid-speaker-name; others are stored") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, completed("memo.wav"), twoSegments, speakerIds = listOf(0, 1))

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        // Each rule of the contract: the client trims, so leading or trailing whitespace is rejected,
                        // as are empty and over-long names and a control character anywhere (inside, or at the edge).
                        val invalidNames =
                            listOf("", "   ", "  Alice  ", " Alice", "Alice ", "A".repeat(41), "Al\tice", "\tAlice", "Bob\u007F")
                        for (invalid in invalidNames) {
                            withClue("name ${Json.encodeToString(invalid)}") {
                                client.rename(id, speakerId = 1, invalid).shouldBeError(HttpStatusCode.BadRequest, "invalid-speaker-name")
                            }
                        }
                        // A rejected name keeps the stored one.
                        client.speakersOf(id) shouldBe speakers("Speaker 1", "Speaker 2")

                        // 40 characters is the longest name accepted.
                        val longest = "A".repeat(40)
                        val atLimit = client.rename(id, speakerId = 1, longest)
                        atLimit.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(atLimit.bodyAsText()).jsonObject shouldHaveShapeOf apiExample("speakers.json")

                        // A valid name is stored exactly as sent, inner space included; the response is the task's speakers.
                        val renamed = client.rename(id, speakerId = 1, "Alice Smith")
                        renamed.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(renamed.bodyAsText()) shouldBe
                            Json.parseToJsonElement("""{ "speakers": ${speakers("Speaker 1", "Alice Smith")} }""")
                        client.speakersOf(id) shouldBe speakers("Speaker 1", "Alice Smith")
                    }
                }
            }
        }

        given("a COMPLETED task with speakers 0, 1 and 2, and another COMPLETED task (queue paused)") {
            `when`("speakers are renamed to names the task already has, in any case, and to their own name") {
                then("another speaker's name, in any case, answers 409 duplicate-speaker-name; own and other tasks' names pass") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, completed("memo.wav"), threeSegments, speakerIds = listOf(0, 1, 2))
                    val otherId = seed(dataDir, completed("other.wav"), twoSegments, speakerIds = listOf(0, 1))

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.rename(id, speakerId = 0, "Alice").status shouldBe HttpStatusCode.OK
                        client.rename(otherId, speakerId = 0, "Bob").status shouldBe HttpStatusCode.OK

                        // Another speaker of the task already has the name: exactly, in another case, or as an
                        // untouched default name (user decision 2026-10-10: compared case-insensitively).
                        for ((speakerId, taken) in listOf(1 to "Alice", 1 to "ALICE", 2 to "alice", 1 to "speaker 3")) {
                            withClue("speaker $speakerId renamed to \"$taken\"") {
                                client.rename(id, speakerId, taken).shouldBeError(HttpStatusCode.Conflict, "duplicate-speaker-name")
                            }
                        }
                        client.speakersOf(id) shouldBe speakers("Alice", "Speaker 2", "Speaker 3")

                        // A speaker's own name, in any case, is no duplicate; another task's names do not count.
                        client.rename(id, speakerId = 0, "Alice").status shouldBe HttpStatusCode.OK
                        client.rename(id, speakerId = 0, "ALICE").status shouldBe HttpStatusCode.OK
                        client.rename(id, speakerId = 2, "Bob").status shouldBe HttpStatusCode.OK
                        client.speakersOf(id) shouldBe speakers("ALICE", "Speaker 2", "Bob")
                    }
                }
            }
        }

        given("a COMPLETED task with speakers 0, 1 and 2, speaker 1 renamed to Alice (queue paused)") {
            `when`("speaker 1's name is reset, and speaker 2's name is reset while speaker 0 is named \"Speaker 3\"") {
                then("a reset stores \"Speaker N\" (N = speakerId + 1) again; a default name another speaker has answers 409") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, completed("memo.wav"), threeSegments, speakerIds = listOf(0, 1, 2))

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.rename(id, speakerId = 1, "Alice").status shouldBe HttpStatusCode.OK

                        // `{ "speaker": null }` is the reset: the server writes the default name (item 09's rule).
                        val reset = client.rename(id, speakerId = 1, null)
                        reset.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(reset.bodyAsText()) shouldBe
                            Json.parseToJsonElement("""{ "speakers": ${speakers("Speaker 1", "Speaker 2", "Speaker 3")} }""")
                        client.speakersOf(id) shouldBe speakers("Speaker 1", "Speaker 2", "Speaker 3")

                        // The default name is checked like any other: speaker 0 now holds "Speaker 3".
                        client.rename(id, speakerId = 2, "Carol").status shouldBe HttpStatusCode.OK
                        client.rename(id, speakerId = 0, "Speaker 3").status shouldBe HttpStatusCode.OK
                        client.rename(id, speakerId = 2, null).shouldBeError(HttpStatusCode.Conflict, "duplicate-speaker-name")
                        client.speakersOf(id) shouldBe speakers("Speaker 3", "Speaker 2", "Carol")
                    }
                }
            }
        }
    })

/** Two segments of a task, one per speaker once `storeSpeakers(listOf(0, 1))` has run. */
private val twoSegments =
    listOf(
        Segment(0, 0, 0, 1_000, " Hello.", speakerId = null),
        Segment(0, 1, 1_000, 2_000, " Hi.", speakerId = null),
    )

/** Three segments of a task, one per speaker once `storeSpeakers(listOf(0, 1, 2))` has run. */
private val threeSegments =
    listOf(
        Segment(0, 0, 0, 1_000, " Hello.", speakerId = null),
        Segment(0, 1, 1_000, 2_000, " Hi.", speakerId = null),
        Segment(0, 2, 2_000, 3_000, " Hey.", speakerId = null),
    )

/** A COMPLETED copy of a freshly queued task. */
private fun completed(name: String): Task = queuedTask(name, createdAt = 1).copy(status = TaskStatus.COMPLETED, finishedAt = 1_000)

/** Stores [task] with [segments] and their [speakerIds] behind a paused queue, so the engine leaves it alone. */
private suspend fun seed(
    dataDir: Path,
    task: Task,
    segments: List<Segment>,
    speakerIds: List<Int>,
): Long =
    Database.open(dataDir).use { db ->
        QueueStateRepository(db).setPaused(true)
        val id =
            TaskRepository(db, TaskWorkDirectories(dataDir))
                .createFromUpload(task, "${task.originalFilename} bytes".toByteArray().inputStream())
        SegmentRepository(db).apply {
            insertAll(id, segments)
            storeSpeakers(id, speakerIds)
        }
        id
    }

/** `PUT /api/tasks/{id}/speakers/{speakerId}` with `{ "speaker": name }`; a null [name] is the reset. */
private suspend fun HttpClient.rename(
    id: Long,
    speakerId: Int,
    name: String?,
): HttpResponse =
    put("/api/tasks/$id/speakers/$speakerId") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("speaker", name) }.toString())
    }

/** The task's speakers as `/turns` reports them. */
private suspend fun HttpClient.speakersOf(id: Long): JsonElement =
    Json.parseToJsonElement(get("/api/tasks/$id/turns").bodyAsText()).jsonObject.getValue("speakers")

/** The JSON speakers list for names given in `speakerId` order. */
private fun speakers(vararg names: String): JsonElement =
    Json.parseToJsonElement(
        names
            .mapIndexed { speakerId, name ->
                buildJsonObject {
                    put("speakerId", speakerId)
                    put("speaker", name)
                }
            }.toString(),
    )

/** Asserts an error response (item 12's contract): [status], the `docs/api/error.json` shape, and the [code]. */
private suspend fun HttpResponse.shouldBeError(
    status: HttpStatusCode,
    code: String,
) {
    this.status shouldBe status
    val body = Json.parseToJsonElement(bodyAsText()).jsonObject
    body shouldHaveShapeOf apiExample("error.json")
    body.getValue("error").jsonPrimitive.content shouldBe code
}
