package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.FailingAfter
import stt.db.GeneratedBytes
import stt.db.MIB
import stt.db.QueueStateRepository
import stt.db.rowCount
import stt.db.singleValue
import stt.db.stageChannel
import java.io.InputStream

/**
 * Item 44, test-list item "(discovered) A failed, rejected or interrupted upload (including a crash mid-upload)
 * leaves none of its bytes in the database": only an upload that becomes a task keeps its original. A staged
 * original that a crash left unclaimed is gone once the app has started, and a body that breaks part-way and a
 * rejected upload leave no staged original and no parts. The rows are read through the running app's own database,
 * so nothing done by a later open can stand in for the app's cleanup.
 */
class UploadLeavesNoBytesSpec :
    BehaviorSpec({
        given("a database holding a staged original that a crash mid-upload left unclaimed (queue paused)") {
            `when`("the app starts, one upload succeeds, one body breaks part-way and one upload is rejected") {
                then("only the created task's original and its parts are stored") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db ->
                        QueueStateRepository(db).setPaused(true)
                        // What a kill mid-upload leaves: 1.5 MiB staged, in two parts, claimed by no task.
                        db.stageChannel(GeneratedBytes(MIB + MIB / 2L).toByteReadChannel())
                        rowCount(db, "original_audio") shouldBe 1
                        rowCount(db, "original_audio_part") shouldBe 2
                    }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        suspend fun upload(
                            filename: String,
                            body: InputStream,
                        ): HttpResponse =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData =
                                    formData {
                                        append(
                                            "file",
                                            ChannelProvider { body.toByteReadChannel() },
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "application/octet-stream")
                                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                            },
                                        )
                                    },
                            )

                        // 2 MiB + 10 bytes: windows of 1 MiB, 1 MiB and 10 bytes — three parts, by hand.
                        val created = upload("kept.wav", GeneratedBytes(2L * MIB + 10))
                        created.status shouldBe HttpStatusCode.Created
                        val createdId =
                            Json
                                .parseToJsonElement(created.bodyAsText())
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong()
                        runCatching { upload("broken.wav", FailingAfter(2L * MIB)) }
                        upload("notes.txt", GeneratedBytes(2L * MIB)).status shouldBe HttpStatusCode.BadRequest

                        val db = application.dependencies.resolve<Database>()
                        singleValue(db, "SELECT count(*) FROM original_audio WHERE task_id IS NULL").toInt() shouldBe 0
                        rowCount(db, "original_audio") shouldBe 1
                        singleValue(db, "SELECT task_id FROM original_audio").toLong() shouldBe createdId
                        rowCount(db, "original_audio_part") shouldBe 3
                    }
                }
            }
        }
    })
