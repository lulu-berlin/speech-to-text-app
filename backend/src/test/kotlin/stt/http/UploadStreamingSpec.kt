package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.AudioRepository
import stt.db.Database
import stt.db.GeneratedBytes
import stt.db.MIB
import stt.db.QueueStateRepository
import stt.db.StagingWatchingSource
import stt.db.TaskRepository
import stt.db.UploadOutcome
import stt.db.queuedTask
import stt.db.rowCount
import stt.db.sha256Hex
import stt.db.withDatabase

/**
 * The review finding (2026-10-10): uploads must be streamed to the spool, no full in-memory copy, and the
 * duplicate check must still answer 409 with the existing task's id when the spooled bytes match. Observation
 * point agreed with the user (option 1): the spool path is driven with a [StagingWatchingSource] (the item-18
 * pattern) so the largest in-memory window stays <= the 1 MiB spool window, the stored sha256 equals the
 * input's true digest, and a duplicate creates nothing; plus a full HTTP upload of ~2.5x the window.
 */
class UploadStreamingSpec :
    BehaviorSpec({

        given("an upload larger than the spool window") {
            `when`("it is spooled through createFromUploadOrDuplicate") {
                then("the largest in-memory window stays <= 1 MiB and the stored sha256 equals the input's") {
                    withDatabase { db ->
                        val size = 2L * MIB + 12_345
                        val source = StagingWatchingSource(GeneratedBytes(size), db)
                        val outcome =
                            TaskRepository(db)
                                .createFromUploadOrDuplicate(
                                    queuedTask("long.wav", createdAt = 1).copy(queuePosition = 0),
                                    source,
                                ) { null }

                        (outcome as UploadOutcome.Created).sha256 shouldBe sha256Hex(GeneratedBytes(size))
                        outcome.sizeBytes shouldBe size
                        source.largestUnstaged.shouldBeLessThanOrEqual(MIB.toLong())
                    }
                }
            }
        }

        given("an upload whose bytes are already stored") {
            `when`("the same bytes are spooled again with the real duplicate lookup") {
                then("Duplicate names the existing task and nothing new is stored") {
                    withDatabase { db ->
                        val tasks = TaskRepository(db)
                        val bytes = "duplicate me".toByteArray()
                        val first =
                            tasks.createFromUploadOrDuplicate(
                                queuedTask("a.wav", createdAt = 1).copy(queuePosition = 0),
                                bytes.inputStream(),
                            ) { null }
                        val duplicate =
                            tasks.createFromUploadOrDuplicate(
                                queuedTask("b.wav", createdAt = 2).copy(queuePosition = 1),
                                bytes.inputStream(),
                            ) { sha -> AudioRepository(db).taskIdOfSha256(sha) }

                        duplicate shouldBe
                            UploadOutcome.Duplicate(
                                (first as UploadOutcome.Created).id,
                                sha256Hex(bytes.inputStream()),
                                bytes.size.toLong(),
                            )
                        rowCount(db, "tasks") shouldBe 1
                        rowCount(db, "audio") shouldBe 1
                        rowCount(db, "original_audio") shouldBe 1
                    }
                }
            }
        }

        given("an upload of about 2.5x the spool window (queue paused)") {
            `when`("uploading it, and uploading the same bytes again") {
                then("201 with the right size, then 409 duplicate-audio with the first task's id; one task exists") {
                    val bytes = ByteArray((2.5 * MIB).toInt()) { it.toByte() }
                    val sha256 = sha256Hex(bytes.inputStream())
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        suspend fun upload(): io.ktor.client.statement.HttpResponse =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData =
                                    formData {
                                        append(
                                            "file",
                                            bytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "audio/x-wav")
                                                append(HttpHeaders.ContentDisposition, "filename=\"long.wav\"")
                                            },
                                        )
                                    },
                            )

                        val first = upload()
                        first.status shouldBe HttpStatusCode.Created
                        val firstBody = Json.parseToJsonElement(first.bodyAsText()).jsonObject
                        val firstId =
                            firstBody
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong()
                        firstBody
                            .getValue("sizeBytes")
                            .jsonPrimitive.content
                            .toLong() shouldBe bytes.size.toLong()
                        Database.open(dataDir).use { db ->
                            AudioRepository(db).metadata(firstId)!!.sha256 shouldBe sha256
                        }

                        val second = upload()
                        second.status shouldBe HttpStatusCode.Conflict
                        val secondBody = Json.parseToJsonElement(second.bodyAsText()).jsonObject
                        secondBody.getValue("error").jsonPrimitive.content shouldBe "duplicate-audio"
                        secondBody
                            .getValue("taskId")
                            .jsonPrimitive.content
                            .toLong() shouldBe firstId

                        Database.open(dataDir).use { db -> rowCount(db, "tasks") shouldBe 1 }
                    }
                }
            }
        }
    })
