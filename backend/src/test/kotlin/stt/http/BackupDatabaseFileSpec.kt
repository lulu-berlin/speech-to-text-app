package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import stt.db.Segment
import stt.db.SegmentRepository
import java.nio.file.Files
import java.nio.file.Path

/**
 * Item 44, test-list item "Backups are the database file alone; restoring it restores the full history and
 * playback": once the app has stopped, `stt.db` by itself holds everything — the finished task with its transcript
 * and stored clip, and the last write the running app made (an upload, still queued). A fresh data directory holding
 * only a copy of that one file serves the same history, transcript and audio.
 */
class BackupDatabaseFileSpec :
    BehaviorSpec({
        given("a finished task with a transcript and a stored clip, and an upload made through the running app") {
            `when`("the app stops and only stt.db is copied into a fresh data directory that a new app opens") {
                then("the restored app serves the same task list, transcript and audio") {
                    val original = tempdir("stt-data").toPath()
                    val clip = testClip()
                    val finished =
                        taskWithTestClip(original, clip, filename = "finished.wav") { db, id ->
                            SegmentRepository(db).insertAll(
                                id,
                                listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = "hello", speakerId = null)),
                            )
                        }

                    lateinit var transcript: String
                    val uploaded =
                        runApp(original) {
                            transcript = client.get("/api/tasks/$finished/transcript?format=txt").bodyAsText()
                            // The running app's last write: with the queue paused, the upload stays QUEUED.
                            val response =
                                client.submitFormWithBinaryData(
                                    url = "/api/tasks",
                                    formData =
                                        formData {
                                            append(
                                                "file",
                                                "the last upload before the backup".toByteArray(),
                                                Headers.build {
                                                    append(HttpHeaders.ContentType, "audio/x-wav")
                                                    append(HttpHeaders.ContentDisposition, "filename=\"last.wav\"")
                                                },
                                            )
                                        },
                                )
                            response.status shouldBe HttpStatusCode.Created
                            Json
                                .parseToJsonElement(response.bodyAsText())
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.long
                        }

                    // The backup: the database file alone, into a directory that holds nothing else.
                    val restored = tempdir("stt-restored").toPath()
                    Files.copy(original.resolve("stt.db"), restored.resolve("stt.db"))

                    runApp(restored) {
                        val ids =
                            Json
                                .parseToJsonElement(client.get("/api/tasks").bodyAsText())
                                .jsonObject
                                .getValue("tasks")
                                .jsonArray
                                .map {
                                    it.jsonObject
                                        .getValue("id")
                                        .jsonPrimitive.long
                                }
                        ids.sorted() shouldBe listOf(finished, uploaded).sorted()
                        client.get("/api/tasks/$finished/transcript?format=txt").bodyAsText() shouldBe transcript
                        val audio = client.get("/api/tasks/$finished/audio")
                        audio.status shouldBe HttpStatusCode.OK
                        audio.bodyAsBytes() shouldBe clip.bytes
                    }
                }
            }
        }
    })

/** Runs the app on [dataDir] for [block] and stops it — a clean stop — returning [block]'s result. */
private fun <T> runApp(
    dataDir: Path,
    block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> T,
): T {
    var result: T? = null
    testApplication {
        application { sttApp(stt.config.testConfig(dataDir)) }
        startApplication()
        result = block()
    }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
