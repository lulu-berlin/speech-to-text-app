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
                                val response = client.rename(id, speakerId = 1, invalid)
                                response.status shouldBe HttpStatusCode.BadRequest
                                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                                body shouldHaveShapeOf apiExample("error.json")
                                body.getValue("error").jsonPrimitive.content shouldBe "invalid-speaker-name"
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
    })

/** Two segments of a task, one per speaker once `storeSpeakers(listOf(0, 1))` has run. */
private val twoSegments =
    listOf(
        Segment(0, 0, 0, 1_000, " Hello.", speakerId = null),
        Segment(0, 1, 1_000, 2_000, " Hi.", speakerId = null),
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

/** `PUT /api/tasks/{id}/speakers/{speakerId}` with `{ "speaker": name }`. */
private suspend fun HttpClient.rename(
    id: Long,
    speakerId: Int,
    name: String,
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
