package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.rowCount
import stt.db.singleValue
import stt.engine.awaitUntil
import kotlin.time.Duration.Companion.seconds

/**
 * Item 41, test-list item "Every staged original is deleted on success, on rejection, on cancellation (client
 * disconnect) and on a mid-stream failure; no unclaimed original remains after each path". Success keeps its
 * original, claimed by the task; a rejected upload, a body that breaks mid-part and a client that disconnects
 * between parts all leave no unclaimed original behind. The disconnect is pinned through
 * [afterFilePartStaged], where a cancellation cancels the staging loop itself.
 */
class UploadCleanupSpec :
    BehaviorSpec({
        val boundary = "stt-cleanup-boundary"

        /** The multipart preamble and one file part's headers, up to its content. */
        fun partPrefix(filename: String): ByteArray =
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                    "Content-Type: audio/x-wav\r\n\r\n"
            ).toByteArray()

        /** One complete file part and its terminating boundary line, but no next part: the body breaks there. */
        fun brokenBody(
            filename: String,
            content: String,
        ): ByteArray = partPrefix(filename) + content.toByteArray() + "\r\n--$boundary\r\n".toByteArray()

        given("an upload endpoint with a paused queue") {
            `when`("an upload succeeds, one is rejected, one body breaks and one loses its client") {
                then("no path leaves an unclaimed staged original, and only the success becomes a task") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        val db = application.dependencies.resolve<Database>()

                        fun unclaimed(): Int = singleValue(db, "SELECT count(*) FROM original_audio WHERE task_id IS NULL").toInt()

                        fun headers(filename: String): Headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/x-wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            }

                        // Success: the original stays, claimed by the created task.
                        val created =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData = formData { append("file", "KEPT".toByteArray(), headers("kept.wav")) },
                            )
                        created.status shouldBe HttpStatusCode.Created
                        rowCount(db, "tasks") shouldBe 1
                        unclaimed() shouldBe 0

                        // Rejection: the staged original is discarded with the 400.
                        val rejected =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData = formData { append("file", "REJECTED".toByteArray(), headers("notes.txt")) },
                            )
                        rejected.status shouldBe HttpStatusCode.BadRequest
                        unclaimed() shouldBe 0

                        // Mid-stream failure: after a complete file part a form field is cut off before its end.
                        val broken = ByteChannel(autoFlush = true)
                        broken.writeFully(
                            brokenBody("broken.wav", "BROKEN BODY") +
                                "Content-Disposition: form-data; name=\"language\"\r\n\r\nen".toByteArray(),
                        )
                        broken.close()
                        runCatching {
                            client.post("/api/tasks") {
                                setBody(ChannelProvider { broken })
                                contentType(ContentType.parse("multipart/form-data; boundary=$boundary"))
                            }
                        }
                        awaitUntil(5.seconds) { unclaimed() == 0 }
                        unclaimed() shouldBe 0

                        // Cancellation (client disconnect): the seam throws where a disconnect cancels the handler,
                        // after the file part was staged; the staged original must still be discarded.
                        afterFilePartStaged = {
                            afterFilePartStaged = null
                            throw CancellationException("client disconnected")
                        }
                        runCatching {
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData = formData { append("file", "GONE".toByteArray(), headers("gone.wav")) },
                            )
                        }
                        awaitUntil(5.seconds) { unclaimed() == 0 }
                        unclaimed() shouldBe 0

                        rowCount(db, "tasks") shouldBe 1
                        rowCount(db, "audio") shouldBe 1
                        rowCount(db, "original_audio") shouldBe 1
                    }
                }
            }
        }
    })
