package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.queue.TaskStatus

class TranscriptDownloadSpec :
    BehaviorSpec({
        given("a COMPLETED task with segments and stored speakers (queue paused)") {
            `when`("downloading its transcript as txt, srt and vtt") {
                then("each returns the rendered output as an attachment with the right content type") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val workDirectories = TaskWorkDirectories(dataDir)
                            val tasks = TaskRepository(db, workDirectories)
                            val id =
                                tasks.createFromUpload(
                                    queuedTask("memo.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "audio bytes".toByteArray().inputStream(),
                                )
                            SegmentRepository(db).apply {
                                insertAll(
                                    id,
                                    listOf(
                                        Segment(0, 0, 0, 1_500, " Hello there.", speakerId = null),
                                        Segment(0, 1, 1_500, 3_250, " again.", speakerId = null),
                                        Segment(1, 0, 3_250, 4_000, " Fine.", speakerId = null),
                                    ),
                                )
                                storeSpeakers(id, listOf(0, 0, 1))
                            }
                            id
                        }

                    // Golden strings derived by hand from item 10's spec (oracle write_diarized_outputs,
                    // ADR 0034 stored names): turns Speaker 1 0–3 250 ms, Speaker 2 3 250–4 000 ms.
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val cases =
                            mapOf(
                                "txt" to
                                    Triple(
                                        "text/plain",
                                        "[Speaker 1]\nHello there.\nagain.\n\n[Speaker 2]\nFine.\n",
                                        "memo.txt",
                                    ),
                                "srt" to
                                    Triple(
                                        "application/x-subrip",
                                        "1\n00:00:00,000 --> 00:00:03,250\nSpeaker 1: Hello there. again.\n\n" +
                                            "2\n00:00:03,250 --> 00:00:04,000\nSpeaker 2: Fine.\n\n",
                                        "memo.srt",
                                    ),
                                "vtt" to
                                    Triple(
                                        "text/vtt",
                                        "WEBVTT\n\n00:00:00.000 --> 00:00:03.250\nSpeaker 1: Hello there. again.\n\n" +
                                            "00:00:03.250 --> 00:00:04.000\nSpeaker 2: Fine.\n\n",
                                        "memo.vtt",
                                    ),
                            )
                        for ((format, expected) in cases) {
                            val response = client.get("/api/tasks/$id/transcript?format=$format")
                            response.status shouldBe HttpStatusCode.OK
                            response.contentType()?.withoutParameters() shouldBe ContentType.parse(expected.first)
                            response.headers[HttpHeaders.ContentDisposition]
                                .shouldContain("attachment")
                                .shouldContain("filename=\"${expected.third}\"")
                            response.bodyAsText() shouldBe expected.second
                        }
                    }
                }
            }
        }

        given("a COMPLETED speakers-off task (queue paused)") {
            `when`("downloading its transcript as txt") {
                then("the rendered output has no speaker lines") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val tasks = TaskRepository(db, TaskWorkDirectories(dataDir))
                            val id =
                                tasks.createFromUpload(
                                    queuedTask("off.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                        speakersMode = SpeakersMode.OFF,
                                    ),
                                    "audio bytes".toByteArray().inputStream(),
                                )
                            SegmentRepository(db).insertAll(id, listOf(Segment(0, 0, 0, 1_000, " One.", speakerId = null)))
                            id
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val response = client.get("/api/tasks/$id/transcript?format=txt")
                        response.status shouldBe HttpStatusCode.OK
                        response.contentType()?.withoutParameters() shouldBe ContentType.parse("text/plain")
                        response.bodyAsText() shouldBe "One.\n"
                    }
                }
            }
        }
    })
