package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.rowCount
import stt.db.singleValue

/**
 * Item 41, test-list item "Two simultaneous identical uploads answer exactly one `201` and one `409
 * duplicate-audio` naming the winning task — never a `500`; the losing request leaves no staged original". The
 * first request is held inside [TaskRepository.beforeCreateFromStaged] — between the upload route's fast duplicate
 * check and its insert — while the second one completes; when the first is released, its insert hits
 * `audio.sha256`'s unique constraint, which must be answered as the contract's 409 naming the winner.
 */
class UploadDuplicateRaceSpec :
    BehaviorSpec({
        given("two simultaneous uploads of the same bytes (queue paused)") {
            `when`("the first is held between the duplicate check and the insert while the second completes") {
                then("exactly one is 201 and one is 409 duplicate-audio naming the winner, and no staged original remains") {
                    val bytes = "RACED AUDIO BYTES".repeat(8).toByteArray()
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        suspend fun upload(client: HttpClient): HttpResponse =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData =
                                    formData {
                                        append(
                                            "file",
                                            bytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "audio/x-wav")
                                                append(HttpHeaders.ContentDisposition, "filename=\"race.wav\"")
                                            },
                                        )
                                    },
                            )
                        startApplication()

                        val tasks = application.dependencies.resolve<TaskRepository>()
                        val held = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        tasks.beforeCreateFromStaged = {
                            tasks.beforeCreateFromStaged = null
                            held.complete(Unit)
                            release.await()
                        }

                        coroutineScope {
                            val first = async { upload(client) }
                            held.await()
                            val second = upload(client)
                            release.complete(Unit)
                            val firstResponse = first.await()

                            val statuses = listOf(firstResponse.status, second.status)
                            statuses.count { it == HttpStatusCode.Created } shouldBe 1
                            statuses.count { it == HttpStatusCode.Conflict } shouldBe 1

                            val (winner, loser) =
                                if (firstResponse.status == HttpStatusCode.Created) {
                                    firstResponse to second
                                } else {
                                    second to firstResponse
                                }
                            val winnerId =
                                Json
                                    .parseToJsonElement(winner.bodyAsText())
                                    .jsonObject
                                    .getValue("id")
                                    .jsonPrimitive
                                    .content
                                    .toLong()
                            loser.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                            val body = Json.parseToJsonElement(loser.bodyAsText()).jsonObject
                            body shouldHaveShapeOf apiExample("error.duplicate-audio.json")
                            body.getValue("error").jsonPrimitive.content shouldBe "duplicate-audio"
                            body
                                .getValue("taskId")
                                .jsonPrimitive
                                .content
                                .toLong() shouldBe winnerId
                            body
                                .getValue("message")
                                .jsonPrimitive
                                .content shouldBe "This file was already uploaded as task $winnerId"

                            val db = application.dependencies.resolve<Database>()
                            rowCount(db, "tasks") shouldBe 1
                            rowCount(db, "audio") shouldBe 1
                            singleValue(db, "SELECT task_id FROM original_audio").toLong() shouldBe winnerId
                            singleValue(db, "SELECT count(*) FROM original_audio WHERE task_id IS NULL").toInt() shouldBe 0
                        }
                    }
                }
            }
        }
    })
