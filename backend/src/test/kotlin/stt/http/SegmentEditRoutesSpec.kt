package stt.http

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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

class SegmentEditRoutesSpec :
    BehaviorSpec({

        suspend fun seed(
            dataDir: Path,
            task: Task,
            segments: List<Segment>,
            speakerIds: List<Int>,
        ): Long =
            Database.open(dataDir).use { db ->
                QueueStateRepository(db).setPaused(true)
                val id =
                    TaskRepository(db)
                        .createFromUpload(task, "${task.originalFilename} bytes".toByteArray().inputStream())
                SegmentRepository(db).apply {
                    insertAll(id, segments)
                    // A speakers-off task has no speakers rows and null speaker ids on its segments.
                    if (speakerIds.isNotEmpty()) storeSpeakers(id, speakerIds)
                }
                id
            }

        fun completed(name: String): Task = queuedTask(name, createdAt = 1).copy(status = TaskStatus.COMPLETED, finishedAt = 1_000)

        fun queued(name: String): Task = queuedTask(name, createdAt = 1).copy(queuePosition = 0)

        fun failed(name: String): Task =
            queuedTask(name, createdAt = 1).copy(
                status = TaskStatus.FAILED,
                finishedAt = 1_000,
                error = "whisper-cli failed",
            )

        given("a COMPLETED task with two turns (queue paused)") {
            `when`("one segment's text is edited") {
                then("the new text is stored and returned by the PUT and by /turns, the timings and speaker unchanged") {
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

                        // The segment ids come from the contract: /turns names every segment by its id.
                        val before = client.turnsOf(id)
                        val beforeTurns = before.getValue("turns").jsonArray
                        val firstTurnSegments = beforeTurns[0].jsonObject.getValue("segments").jsonArray
                        val firstSegmentId =
                            firstTurnSegments[0]
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.long
                        val editedSegmentId =
                            firstTurnSegments[1]
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.long
                        val lastSegmentId =
                            beforeTurns[1]
                                .jsonObject
                                .getValue("segments")
                                .jsonArray[0]
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.long

                        val put = client.editSegment(id, editedSegmentId, "Corrected sentence.")
                        put.status shouldBe HttpStatusCode.OK
                        put.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(put.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("turns.json")

                        // Derived by hand from the contract: the edited segment keeps its window and speaker, and its
                        // turn's text is the trimmed segment texts joined with single spaces.
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
                                      "speakerId": 0, "startMs": 0, "endMs": 3250, "text": "Hello there. Corrected sentence.",
                                      "segments": [
                                        { "id": $firstSegmentId, "startMs": 0, "endMs": 1500, "text": "Hello there.", "edited": false },
                                        { "id": $editedSegmentId, "startMs": 1500, "endMs": 3250, "text": "Corrected sentence.", "edited": true }
                                      ]
                                    },
                                    {
                                      "speakerId": 1, "startMs": 3250, "endMs": 4000, "text": "Fine.",
                                      "segments": [ { "id": $lastSegmentId, "startMs": 3250, "endMs": 4000, "text": "Fine.", "edited": false } ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )

                        // The open views' read agrees with the write's answer.
                        client.turnsOf(id) shouldBe body
                    }

                    // In the database: the edited row holds the new text; its window and speaker are as seeded.
                    Database.open(dataDir).use { db ->
                        val stored = SegmentRepository(db).findByTask(id)
                        stored.map { it.text } shouldBe
                            listOf(" Hello there.", "Corrected sentence.", " Fine.")
                        stored.map { Triple(it.startMs, it.endMs, it.speakerId) } shouldBe
                            listOf(
                                Triple(0L, 1_500L, 0),
                                Triple(1_500L, 3_250L, 0),
                                Triple(3_250L, 4_000L, 1),
                            )
                    }
                }
            }
        }

        given("a QUEUED and a FAILED task with segments, and a COMPLETED task (queue paused)") {
            `when`("a segment of an unfinished task, or an unknown task or segment, is edited") {
                then(
                    "409 not-completed for the unfinished ones and 404 not-found for unknown ids or another task's segment; nothing is stored",
                ) {
                    val dataDir = tempdir("stt-data").toPath()
                    val completedId =
                        seed(
                            dataDir,
                            completed("done.wav"),
                            listOf(Segment(0, 0, 0, 1_000, " Done.", speakerId = null)),
                            speakerIds = listOf(0),
                        )
                    val queuedId =
                        seed(
                            dataDir,
                            queued("queued.wav"),
                            listOf(Segment(0, 0, 0, 1_000, " Queued.", speakerId = null)),
                            speakerIds = listOf(0),
                        )
                    val failedId =
                        seed(
                            dataDir,
                            failed("failed.wav"),
                            listOf(Segment(0, 0, 0, 1_000, " Failed.", speakerId = null)),
                            speakerIds = listOf(0),
                        )
                    // /turns answers 409 for both unfinished tasks, so their segment ids come from the stored rows.
                    val queuedSegmentId = segmentIds(dataDir, queuedId).single()
                    val failedSegmentId = segmentIds(dataDir, failedId).single()

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        withClue("QUEUED task") {
                            client
                                .editSegment(queuedId, queuedSegmentId, "Changed.")
                                .shouldBeError(HttpStatusCode.Conflict, "not-completed")
                        }
                        withClue("FAILED task") {
                            client
                                .editSegment(failedId, failedSegmentId, "Changed.")
                                .shouldBeError(HttpStatusCode.Conflict, "not-completed")
                        }
                        withClue("unknown task") {
                            client
                                .editSegment(999, 1, "Changed.")
                                .shouldBeError(HttpStatusCode.NotFound, "not-found")
                        }
                        withClue("non-numeric task") {
                            client
                                .editSegment("abc", "1", "Changed.")
                                .shouldBeError(HttpStatusCode.NotFound, "not-found")
                        }
                        withClue("unknown segment") {
                            client
                                .editSegment(completedId, 999, "Changed.")
                                .shouldBeError(HttpStatusCode.NotFound, "not-found")
                        }
                        withClue("non-numeric segment") {
                            client
                                .editSegment(completedId.toString(), "abc", "Changed.")
                                .shouldBeError(HttpStatusCode.NotFound, "not-found")
                        }
                        withClue("another task's segment") {
                            client
                                .editSegment(completedId, queuedSegmentId, "Changed.")
                                .shouldBeError(HttpStatusCode.NotFound, "not-found")
                        }
                    }

                    // Every rejected edit stored nothing: each task's seeded text is still there.
                    Database.open(dataDir).use { db ->
                        val segments = SegmentRepository(db)
                        segments.findByTask(completedId).map { it.text } shouldBe listOf(" Done.")
                        segments.findByTask(queuedId).map { it.text } shouldBe listOf(" Queued.")
                        segments.findByTask(failedId).map { it.text } shouldBe listOf(" Failed.")
                    }
                }
            }
        }

        given("a COMPLETED task with one stored segment (queue paused)") {
            `when`("empty, whitespace-only or control-character text is edited in") {
                then("each answers 400 invalid-segment-text and leaves the stored text") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        seed(
                            dataDir,
                            completed("memo.wav"),
                            listOf(Segment(0, 0, 0, 1_000, " Original.", speakerId = null)),
                            speakerIds = listOf(0),
                        )
                    val segmentId = segmentIds(dataDir, id).single()

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        // The client trims, so a whitespace-only result reaches the API as "": both are rejected.
                        val invalidTexts = listOf("", "   ", "\t\n", "Hello\nworld", "Hi\u0000there", "\u007F")
                        for (invalid in invalidTexts) {
                            withClue("text ${Json.encodeToString(invalid)}") {
                                client
                                    .editSegment(id, segmentId, invalid)
                                    .shouldBeError(HttpStatusCode.BadRequest, "invalid-segment-text")
                            }
                        }
                    }

                    // Nothing was stored by any rejected edit.
                    Database.open(dataDir).use { db ->
                        SegmentRepository(db).findByTask(id).map { it.text } shouldBe listOf(" Original.")
                    }
                }
            }

            `when`("a valid text is edited in") {
                then("it is stored as sent — internal spacing kept — with no length cap") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        seed(
                            dataDir,
                            completed("memo.wav"),
                            listOf(Segment(0, 0, 0, 1_000, " Original.", speakerId = null)),
                            speakerIds = listOf(0),
                        )
                    val segmentId = segmentIds(dataDir, id).single()
                    val longText = "Two  spaces and MiXeD case " + "x".repeat(10_000)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val put = client.editSegment(id, segmentId, longText)
                        put.status shouldBe HttpStatusCode.OK
                        val edited =
                            Json
                                .parseToJsonElement(put.bodyAsText())
                                .jsonObject
                                .getValue("turns")
                                .jsonArray[0]
                                .jsonObject
                                .getValue("segments")
                                .jsonArray[0]
                                .jsonObject
                        edited.getValue("id").jsonPrimitive.long shouldBe segmentId
                        edited.getValue("text").jsonPrimitive.content shouldBe longText
                    }

                    // Stored byte for byte, and the same row still holds its window and speaker.
                    Database.open(dataDir).use { db ->
                        val stored = SegmentRepository(db).findByTask(id).single()
                        stored.text shouldBe longText
                        Triple(stored.startMs, stored.endMs, stored.speakerId) shouldBe Triple(0L, 1_000L, 0)
                    }
                }
            }
        }

        given("a COMPLETED speakers-off task with two segments (queue paused)") {
            `when`("one segment's text is edited") {
                then("it is stored and returned as one null-speaker turn per segment, no speaker attached") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        seed(
                            dataDir,
                            completed("memo.wav").copy(speakersMode = SpeakersMode.OFF),
                            listOf(
                                Segment(0, 0, 0, 1_000, " Remember milk.", speakerId = null),
                                Segment(0, 1, 1_000, 2_000, " Call dentist.", speakerId = null),
                            ),
                            speakerIds = emptyList(),
                        )
                    val segmentIds = segmentIds(dataDir, id)
                    val firstSegmentId = segmentIds[0]
                    val secondSegmentId = segmentIds[1]

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val put = client.editSegment(id, firstSegmentId, "Corrected milk.")
                        put.status shouldBe HttpStatusCode.OK
                        put.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(put.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("turns.speakers-off.json")

                        // Derived by hand from the contract: each segment its own turn, no speaker; trimmed text.
                        body shouldBe
                            Json.parseToJsonElement(
                                """
                                {
                                  "speakers": [],
                                  "hasMerges": false,
                                  "turns": [
                                    {
                                      "speakerId": null, "startMs": 0, "endMs": 1000, "text": "Corrected milk.",
                                      "segments": [ { "id": $firstSegmentId, "startMs": 0, "endMs": 1000, "text": "Corrected milk.", "edited": true } ]
                                    },
                                    {
                                      "speakerId": null, "startMs": 1000, "endMs": 2000, "text": "Call dentist.",
                                      "segments": [ { "id": $secondSegmentId, "startMs": 1000, "endMs": 2000, "text": "Call dentist.", "edited": false } ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                        client.turnsOf(id) shouldBe body
                    }

                    // In the database: the edited row holds the new text and still has no speaker.
                    Database.open(dataDir).use { db ->
                        val stored = SegmentRepository(db).findByTask(id)
                        stored.map { it.text } shouldBe listOf("Corrected milk.", " Call dentist.")
                        stored.map { Triple(it.startMs, it.endMs, it.speakerId) } shouldBe
                            listOf(
                                Triple(0L, 1_000L, null),
                                Triple(1_000L, 2_000L, null),
                            )
                    }
                }
            }
        }

        given("a COMPLETED task with one segment and an SSE client (queue paused, real server)") {
            `when`("a rejected edit is followed by a valid one") {
                then("only the stored edit emits segment-edited, carrying the stored segment with its text trimmed") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        seed(
                            dataDir,
                            completed("memo.wav"),
                            listOf(Segment(0, 0, 0, 1_000, " Original.", speakerId = null)),
                            speakerIds = listOf(0),
                        )
                    val segmentId = segmentIds(dataDir, id).single()

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        // The rejected edit stores and emits nothing, so the valid edit's frame is the next one.
                        server.command("PUT", "/api/tasks/$id/segments/$segmentId", """{ "text": "" }""") shouldBe 400
                        server.command("PUT", "/api/tasks/$id/segments/$segmentId", """{ "text": "  Corrected.  " }""") shouldBe 200

                        val (name, data) = events.readEvent()
                        name shouldBe "segment-edited"
                        val payload = Json.parseToJsonElement(data).jsonObject
                        payload shouldHaveShapeOf apiExample("segment-edited.json")
                        // The event carries the segment the view shows: trimmed text, the stored window and id.
                        payload shouldBe
                            Json.parseToJsonElement(
                                """
                                {
                                  "id": $id,
                                  "segment": { "id": $segmentId, "startMs": 0, "endMs": 1000, "text": "Corrected.", "edited": true }
                                }
                                """.trimIndent(),
                            )
                    }

                    // The row itself kept the text as sent.
                    Database.open(dataDir).use { db ->
                        SegmentRepository(db).findByTask(id).single().text shouldBe "  Corrected.  "
                    }
                }
            }
        }

        given("a COMPLETED task with two turns and one edited segment (queue paused)") {
            `when`("its transcript is downloaded as txt, srt and vtt") {
                then("each download renders the edited text, trimmed, in the segment's place") {
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
                    val editedSegmentId = segmentIds(dataDir, id)[1]

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.editSegment(id, editedSegmentId, "  Corrected sentence.  ").status shouldBe HttpStatusCode.OK

                        // Derived by hand from item 10's rules for the same seed as
                        // TranscriptDownloadSpec: turns Speaker 1 0–3 250 ms, Speaker 2 3 250–4 000 ms, with " again."
                        // replaced by the edit, trimmed at read time (ADR 0030).
                        val expected =
                            mapOf(
                                "txt" to "[Speaker 1]\nHello there.\nCorrected sentence.\n\n[Speaker 2]\nFine.\n",
                                "srt" to
                                    "1\n00:00:00,000 --> 00:00:03,250\nSpeaker 1: Hello there. Corrected sentence.\n\n" +
                                    "2\n00:00:03,250 --> 00:00:04,000\nSpeaker 2: Fine.\n\n",
                                "vtt" to
                                    "WEBVTT\n\n00:00:00.000 --> 00:00:03.250\nSpeaker 1: Hello there. Corrected sentence.\n\n" +
                                    "00:00:03.250 --> 00:00:04.000\nSpeaker 2: Fine.\n\n",
                            )
                        for ((format, body) in expected) {
                            withClue(format) {
                                val response = client.get("/api/tasks/$id/transcript?format=$format")
                                response.status shouldBe HttpStatusCode.OK
                                response.bodyAsText() shouldBe body
                            }
                        }
                    }
                }
            }
        }
    })

/** The task's stored segment ids, in `(chunk_idx, seq)` order (read from the database, since /turns refuses it). */
private suspend fun segmentIds(
    dataDir: Path,
    taskId: Long,
): List<Long> =
    Database.open(dataDir).use { db ->
        SegmentRepository(db).findByTask(taskId).map { requireNotNull(it.id) }
    }

/** `PUT /api/tasks/{id}/segments/{segmentId}` with `{ "text": text }`. */
private suspend fun HttpClient.editSegment(
    id: Long,
    segmentId: Long,
    text: String,
) = editSegment(id.toString(), segmentId.toString(), text)

/** The same request with ids as sent (a non-numeric one is the 404 case). */
private suspend fun HttpClient.editSegment(
    id: String,
    segmentId: String,
    text: String,
) = put("/api/tasks/$id/segments/$segmentId") {
    contentType(ContentType.Application.Json)
    setBody(buildJsonObject { put("text", text) }.toString())
}
