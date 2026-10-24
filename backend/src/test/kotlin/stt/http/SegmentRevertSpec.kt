package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Path

/**
 * Item 52 / ADR 0061: a segment keeps whisper's original text, so an edit can be reverted — and the downloads
 * always show the stored text (the edited line, never the original).
 */
class SegmentRevertSpec :
    BehaviorSpec({

        suspend fun seed(dataDir: Path): Long =
            Database.open(dataDir).use { db ->
                QueueStateRepository(db).setPaused(true)
                val task =
                    queuedTask("song.wav", createdAt = 1).copy(status = TaskStatus.COMPLETED, finishedAt = 1_000)
                val id =
                    TaskRepository(db)
                        .createFromUpload(task, "song.wav bytes".toByteArray().inputStream())
                SegmentRepository(db).apply {
                    insertAll(
                        id,
                        listOf(
                            Segment(0, 0, 0, 1_500, " Hello there.", speakerId = null),
                            Segment(0, 1, 1_500, 3_250, " again.", speakerId = null),
                        ),
                    )
                    storeSpeakers(id, listOf(0, 0))
                }
                id
            }

        given("a COMPLETED task whose first segment was edited away from whisper's text") {
            `when`("that segment is reverted") {
                then("whisper's text comes back in the row, the turns and the downloads, and edited goes false") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val segmentId = client.firstSegmentId(id)
                        client.editSegment(id, segmentId, "Corrected line.")

                        // After the edit: the turns say it is edited, and the download is the edited line.
                        val edited = client.segment(id, segmentId)
                        edited.getValue("text").jsonPrimitive.content shouldBe "Corrected line."
                        edited.getValue("edited").jsonPrimitive.content shouldBe "true"
                        val afterEdit = client.download(id)
                        afterEdit.contains("Corrected line.") shouldBe true
                        afterEdit.contains("Hello there.") shouldBe false

                        val response = client.post("/api/tasks/$id/segments/$segmentId/revert")
                        response.status shouldBe HttpStatusCode.OK

                        // The answer is the updated turns: whisper's text, no longer edited.
                        val reverted =
                            Json
                                .parseToJsonElement(response.bodyAsText())
                                .jsonObject
                                .segment(segmentId)
                        reverted.getValue("text").jsonPrimitive.content shouldBe "Hello there."
                        reverted.getValue("edited").jsonPrimitive.content shouldBe "false"

                        // The download follows the stored text back to whisper's line.
                        val afterRevert = client.download(id)
                        afterRevert.contains("Hello there.") shouldBe true
                        afterRevert.contains("Corrected line.") shouldBe false

                        // The row keeps whisper's line stored.
                        Database.open(dataDir).use { db ->
                            SegmentRepository(db)
                                .findByTask(id)
                                .first { it.id == segmentId }
                                .text shouldBe " Hello there."
                        }
                    }
                }
            }
        }
    })

private suspend fun HttpClient.turns(taskId: Long): JsonObject =
    Json.parseToJsonElement(get("/api/tasks/$taskId/turns").bodyAsText()).jsonObject

private fun JsonObject.segment(segmentId: Long): JsonObject =
    getValue("turns")
        .jsonArray
        .flatMap { it.jsonObject.getValue("segments").jsonArray }
        .map { it.jsonObject }
        .first { it.getValue("id").jsonPrimitive.long == segmentId }

private suspend fun HttpClient.segment(
    taskId: Long,
    segmentId: Long,
): JsonObject = turns(taskId).segment(segmentId)

private suspend fun HttpClient.firstSegmentId(taskId: Long): Long =
    turns(taskId)
        .getValue("turns")
        .jsonArray
        .first()
        .jsonObject
        .getValue("segments")
        .jsonArray
        .first()
        .jsonObject
        .getValue("id")
        .jsonPrimitive.long

private suspend fun HttpClient.download(taskId: Long): String = get("/api/tasks/$taskId/transcript?format=txt").bodyAsText()

private suspend fun HttpClient.editSegment(
    taskId: Long,
    segmentId: Long,
    text: String,
) {
    val response =
        put("/api/tasks/$taskId/segments/$segmentId") {
            contentType(ContentType.Application.Json)
            setBody("""{"text": "$text"}""")
        }
    response.status shouldBe HttpStatusCode.OK
}
