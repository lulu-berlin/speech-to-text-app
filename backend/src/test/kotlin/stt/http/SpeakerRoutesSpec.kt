package stt.http

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.Speaker
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

        given("a COMPLETED task with speakers 0 and 1, and an SSE client (queue paused, real server)") {
            `when`("a rename is rejected, then speaker 1 is renamed to Alice, then its name is reset") {
                then("each stored rename and reset emits speakers-renamed with the task's stored names; the rejected one emits nothing") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, completed("memo.wav"), twoSegments, speakerIds = listOf(0, 1))

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        // Rejected (untrimmed): nothing is stored, so nothing is emitted; the next frame is the
                        // stored rename's, and its names can only come from reading the stored row.
                        server.command("PUT", "/api/tasks/$id/speakers/1", """{ "speaker": " Alice" }""") shouldBe 400
                        server.command("PUT", "/api/tasks/$id/speakers/1", """{ "speaker": "Alice" }""") shouldBe 200
                        val (renamedName, renamedData) = events.readEvent()
                        renamedName shouldBe "speakers-renamed"
                        val renamed = Json.parseToJsonElement(renamedData).jsonObject
                        renamed shouldHaveShapeOf apiExample("speakers-renamed.json")
                        renamed shouldBe Json.parseToJsonElement("""{ "id": $id, "speakers": ${speakers("Speaker 1", "Alice")} }""")

                        // A name reset is a rename too (the spec's Events bullet).
                        server.command("PUT", "/api/tasks/$id/speakers/1", """{ "speaker": null }""") shouldBe 200
                        val (resetName, resetData) = events.readEvent()
                        resetName shouldBe "speakers-renamed"
                        Json.parseToJsonElement(resetData) shouldBe
                            Json.parseToJsonElement("""{ "id": $id, "speakers": ${speakers("Speaker 1", "Speaker 2")} }""")
                    }
                }
            }
        }

        given("a COMPLETED task whose segments are spoken by speakers 0, 0, 1 (queue paused)") {
            `when`("speaker 0 is renamed to Alice and the turns are read again") {
                then("/turns lists the new name, and its turns are exactly the turns before the rename") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seed(dataDir, completed("memo.wav"), threeSegments, speakerIds = listOf(0, 0, 1))

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        val before = client.turnsOf(id)

                        client.rename(id, speakerId = 0, "Alice").status shouldBe HttpStatusCode.OK

                        val after = client.turnsOf(id)
                        after shouldHaveShapeOf apiExample("turns.json")
                        after.getValue("speakers") shouldBe speakers("Alice", "Speaker 2")
                        // Turns name their speaker only by speakerId, so a rename leaves them as they were: segments
                        // 0 and 1 form speaker 0's turn, segment 2 speaker 1's.
                        after.getValue("turns") shouldBe before.getValue("turns")
                        val turnSpeakers = after.getValue("turns").jsonArray.map { it.jsonObject.getValue("speakerId") }
                        turnSpeakers shouldBe listOf(JsonPrimitive(0), JsonPrimitive(1))
                    }
                }
            }
        }

        given("a COMPLETED task whose segments are spoken by speakers 0, 1, 0, 2, speaker 0 named Alice (queue paused)") {
            `when`("speakers 0 and 1 are merged into speaker 0") {
                then("the survivor answers for both: /turns shows one turn across the former boundary; no segment row changes") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seedSpeakers0102(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.rename(id, speakerId = 0, "Alice").status shouldBe HttpStatusCode.OK

                        val merged = client.merge(id, group = listOf(0, 1), into = 0)
                        merged.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(merged.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("speakers.json")
                        // The effective speakers: the survivor keeps its id and name; speaker 1 is no longer listed.
                        body shouldBe
                            Json.parseToJsonElement(
                                """{ "speakers": [ { "speakerId": 0, "speaker": "Alice" }, { "speakerId": 2, "speaker": "Speaker 3" } ] }""",
                            )

                        // Derived by hand from the contract: speaker 1's segment now reads as speaker 0's, so segments
                        // 0–2 are one turn (first start, last end, texts trimmed and joined); segment 3 stays apart.
                        client.turnsOf(id) shouldBe
                            Json.parseToJsonElement(
                                """
                                {
                                  "speakers": [
                                    { "speakerId": 0, "speaker": "Alice" },
                                    { "speakerId": 2, "speaker": "Speaker 3" }
                                  ],
                                  "turns": [
                                    {
                                      "speakerId": 0, "startMs": 0, "endMs": 3000, "text": "Hello. Hi. Hey.",
                                      "segments": [
                                        { "startMs": 0, "endMs": 1000, "text": "Hello." },
                                        { "startMs": 1000, "endMs": 2000, "text": "Hi." },
                                        { "startMs": 2000, "endMs": 3000, "text": "Hey." }
                                      ]
                                    },
                                    {
                                      "speakerId": 2, "startMs": 3000, "endMs": 4000, "text": "Bye.",
                                      "segments": [ { "startMs": 3000, "endMs": 4000, "text": "Bye." } ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                    }

                    // In the database: every segment row as seeded (speaker 1's included), and all three speakers kept.
                    Database.open(dataDir).use { db ->
                        val segments = SegmentRepository(db)
                        val seeded = fourSegments.zip(listOf(0, 1, 0, 2)) { segment, speakerId -> segment.copy(speakerId = speakerId) }
                        segments.findByTask(id) shouldBe seeded
                        segments.findSpeakers(id) shouldBe
                            listOf(Speaker(0, "Alice"), Speaker(1, "Speaker 2"), Speaker(2, "Speaker 3"))
                    }
                }
            }
        }

        given("the merged task of the previous scenario: speakers 0, 1, 0, 2, speaker 0 named Alice, {0, 1} merged into 0") {
            `when`("its transcript is downloaded as txt, srt and vtt") {
                then("every folded segment carries the survivor's name, in one turn across the former boundary") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seedSpeakers0102(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.nameAliceAndMerge1Into0(id)

                        // Derived by hand from item 10's formats (as TranscriptDownloadSpec's goldens) for the turns
                        // Alice 0–3 000 ms (Hello., Hi., Hey.) and Speaker 3 3 000–4 000 ms (Bye.).
                        val expected =
                            mapOf(
                                "txt" to "[Alice]\nHello.\nHi.\nHey.\n\n[Speaker 3]\nBye.\n",
                                "srt" to
                                    "1\n00:00:00,000 --> 00:00:03,000\nAlice: Hello. Hi. Hey.\n\n" +
                                    "2\n00:00:03,000 --> 00:00:04,000\nSpeaker 3: Bye.\n\n",
                                "vtt" to
                                    "WEBVTT\n\n00:00:00.000 --> 00:00:03.000\nAlice: Hello. Hi. Hey.\n\n" +
                                    "00:00:03.000 --> 00:00:04.000\nSpeaker 3: Bye.\n\n",
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

        given("cycle 8's task with speakers 0, 1 and 2 (queue paused)") {
            `when`("merges are requested with into outside the group, one speaker, and members or into already merged") {
                then("each answers 400 invalid-merge and stores nothing; only the valid merge takes effect") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seedSpeakers0102(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        // `into` must be one of the group, and the group must name at least two speakers.
                        for ((group, into) in listOf(listOf(0, 1) to 2, listOf(1) to 1, listOf(1, 1) to 1)) {
                            withClue("group $group into $into") {
                                client.merge(id, group, into).shouldBeError(HttpStatusCode.BadRequest, "invalid-merge")
                            }
                        }
                        client.speakersOf(id) shouldBe speakers("Speaker 1", "Speaker 2", "Speaker 3")

                        client.merge(id, group = listOf(0, 1), into = 0).status shouldBe HttpStatusCode.OK

                        // Speaker 1 is now merged into 0: it can be neither a member nor the survivor of another merge.
                        for ((group, into) in listOf(listOf(1, 2) to 2, listOf(2, 1) to 1)) {
                            withClue("group $group into $into") {
                                client.merge(id, group, into).shouldBeError(HttpStatusCode.BadRequest, "invalid-merge")
                            }
                        }
                        client.speakersOf(id) shouldBe
                            Json.parseToJsonElement(
                                """[ { "speakerId": 0, "speaker": "Speaker 1" }, { "speakerId": 2, "speaker": "Speaker 3" } ]""",
                            )
                    }
                }
            }
        }

        given("cycle 8's task with speakers 0, 1 and 2, and an SSE client (queue paused, real server)") {
            `when`("an invalid merge is requested, then {0, 1} is merged into 0") {
                then("the stored merge emits speakers-merged with the effective speakers; the rejected one emits nothing") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seedSpeakers0102(dataDir)

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        // Rejected (`into` outside the group): nothing stored, nothing emitted; the next frame is the
                        // stored merge's, whose speakers can only come from reading the stored mapping.
                        server.command("POST", "/api/tasks/$id/speakers/merge", """{ "group": [0, 1], "into": 2 }""") shouldBe 400
                        server.command("POST", "/api/tasks/$id/speakers/merge", """{ "group": [0, 1], "into": 0 }""") shouldBe 200
                        val (name, data) = events.readEvent()
                        name shouldBe "speakers-merged"
                        val merged = Json.parseToJsonElement(data).jsonObject
                        merged shouldHaveShapeOf apiExample("speakers-merged.json")
                        merged shouldBe
                            Json.parseToJsonElement(
                                """
                                { "id": $id, "speakers": [ { "speakerId": 0, "speaker": "Speaker 1" }, { "speakerId": 2, "speaker": "Speaker 3" } ] }
                                """.trimIndent(),
                            )
                    }
                }
            }
        }

        given("cycle 8's task with speaker 1 named Bob, and an SSE client (queue paused, real server)") {
            `when`("{0, 1} is merged into 0 and then all merges are reset") {
                then("the speakers, turns and downloads are as before the merge, and the reset emits speakers-merged") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seedSpeakers0102(dataDir)
                    val allSpeakers =
                        """[ { "speakerId": 0, "speaker": "Speaker 1" }, { "speakerId": 1, "speaker": "Bob" }, """ +
                            """{ "speakerId": 2, "speaker": "Speaker 3" } ]"""

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"
                        server.command("PUT", "/api/tasks/$id/speakers/1", """{ "speaker": "Bob" }""") shouldBe 200
                        events.readEvent().first shouldBe "speakers-renamed"

                        // The originals, read before any merge.
                        val formats = listOf("txt", "srt", "vtt")
                        val turnsBefore = server.request("GET", "/api/tasks/$id/turns")
                        val downloadsBefore = formats.map { server.request("GET", "/api/tasks/$id/transcript?format=$it") }

                        server.command("POST", "/api/tasks/$id/speakers/merge", """{ "group": [0, 1], "into": 0 }""") shouldBe 200
                        events.readEvent().first shouldBe "speakers-merged"

                        // The reset answers every speaker again, the hidden one with the name it had (Bob) …
                        val (status, body) = server.request("DELETE", "/api/tasks/$id/speakers/merges")
                        status shouldBe 200
                        Json.parseToJsonElement(body) shouldBe Json.parseToJsonElement("""{ "speakers": $allSpeakers }""")
                        // … and emits speakers-merged with them, after the mapping is gone.
                        val (name, data) = events.readEvent()
                        name shouldBe "speakers-merged"
                        Json.parseToJsonElement(data) shouldBe Json.parseToJsonElement("""{ "id": $id, "speakers": $allSpeakers }""")

                        // Turns and downloads are exactly as they were before the merge.
                        server.request("GET", "/api/tasks/$id/turns") shouldBe turnsBefore
                        formats.map { server.request("GET", "/api/tasks/$id/transcript?format=$it") } shouldBe downloadsBefore
                    }
                }
            }
        }

        given("a QUEUED and a FAILED task with speakers, and cycle 8's COMPLETED task (queue paused)") {
            `when`("speakers are renamed, merged or reset there, on unknown tasks, and with unknown speakers") {
                then("a task that is not COMPLETED answers 409 not-completed; an unknown task or speaker 404 not-found") {
                    val dataDir = tempdir("stt-data").toPath()
                    val queued =
                        seed(dataDir, queuedTask("q.wav", createdAt = 1).copy(queuePosition = 0), twoSegments, speakerIds = listOf(0, 1))
                    val failed =
                        seed(dataDir, queuedTask("f.wav", createdAt = 2).copy(status = TaskStatus.FAILED), twoSegments, listOf(0, 1))
                    val id = seedSpeakers0102(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        // Only a COMPLETED task's speakers can be renamed or merged (the spec's decision covers reset too).
                        for (task in listOf(queued, failed)) {
                            withClue("task $task") {
                                client.rename(task, speakerId = 0, "Alice").shouldBeError(HttpStatusCode.Conflict, "not-completed")
                                client.merge(task, group = listOf(0, 1), into = 0).shouldBeError(HttpStatusCode.Conflict, "not-completed")
                                client.delete("/api/tasks/$task/speakers/merges").shouldBeError(HttpStatusCode.Conflict, "not-completed")
                            }
                        }

                        // An unknown or non-numeric task id.
                        for (task in listOf("999", "abc")) {
                            withClue("task $task") {
                                client
                                    .put("/api/tasks/$task/speakers/0") {
                                        contentType(ContentType.Application.Json)
                                        setBody("""{ "speaker": "Alice" }""")
                                    }.shouldBeError(HttpStatusCode.NotFound, "not-found")
                                client
                                    .post("/api/tasks/$task/speakers/merge") {
                                        contentType(ContentType.Application.Json)
                                        setBody("""{ "group": [0, 1], "into": 0 }""")
                                    }.shouldBeError(HttpStatusCode.NotFound, "not-found")
                                client.delete("/api/tasks/$task/speakers/merges").shouldBeError(HttpStatusCode.NotFound, "not-found")
                            }
                        }

                        // An unknown or non-numeric speaker of a COMPLETED task, renamed or named in a merge.
                        client.rename(id, speakerId = 7, "Alice").shouldBeError(HttpStatusCode.NotFound, "not-found")
                        client
                            .put("/api/tasks/$id/speakers/x") {
                                contentType(ContentType.Application.Json)
                                setBody("""{ "speaker": "Alice" }""")
                            }.shouldBeError(HttpStatusCode.NotFound, "not-found")
                        client.merge(id, group = listOf(0, 7), into = 0).shouldBeError(HttpStatusCode.NotFound, "not-found")
                        client.merge(id, group = listOf(0, 1), into = 7).shouldBeError(HttpStatusCode.NotFound, "not-found")

                        // Nothing changed anywhere.
                        client.speakersOf(id) shouldBe speakers("Speaker 1", "Speaker 2", "Speaker 3")
                    }
                }
            }
        }

        given("cycle 8's merged task: speaker 0 named Alice, {0, 1} merged into 0 (queue paused)") {
            `when`("the hidden speaker 1 is renamed, or its name reset") {
                then("both answer 404 not-found, and after resetting all merges speaker 1 still has its old name") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id = seedSpeakers0102(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        client.nameAliceAndMerge1Into0(id)

                        // A speaker hidden by a merge is not one of the task's speakers as shown (user decision 2026-10-10).
                        client.rename(id, speakerId = 1, "Bob").shouldBeError(HttpStatusCode.NotFound, "not-found")
                        client.rename(id, speakerId = 1, null).shouldBeError(HttpStatusCode.NotFound, "not-found")

                        client.delete("/api/tasks/$id/speakers/merges").status shouldBe HttpStatusCode.OK
                        client.speakersOf(id) shouldBe speakers("Alice", "Speaker 2", "Speaker 3")
                    }
                }
            }
        }
    })

/** Cycle 8's task: a COMPLETED task whose four segments are spoken by speakers 0, 1, 0, 2. */
private suspend fun seedSpeakers0102(dataDir: Path): Long =
    seed(dataDir, completed("memo.wav"), fourSegments, speakerIds = listOf(0, 1, 0, 2))

/** Cycle 8's merge on [seedSpeakers0102]'s task: speaker 0 named `Alice`, then `{0, 1}` merged into 0. */
private suspend fun HttpClient.nameAliceAndMerge1Into0(id: Long) {
    rename(id, speakerId = 0, "Alice").status shouldBe HttpStatusCode.OK
    merge(id, group = listOf(0, 1), into = 0).status shouldBe HttpStatusCode.OK
}

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

/** Four segments of a task, for speakers stored as `[0, 1, 0, 2]`. */
private val fourSegments =
    listOf(
        Segment(0, 0, 0, 1_000, " Hello.", speakerId = null),
        Segment(0, 1, 1_000, 2_000, " Hi.", speakerId = null),
        Segment(0, 2, 2_000, 3_000, " Hey.", speakerId = null),
        Segment(0, 3, 3_000, 4_000, " Bye.", speakerId = null),
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

/** `POST /api/tasks/{id}/speakers/merge` with `{ "group": group, "into": into }`. */
private suspend fun HttpClient.merge(
    id: Long,
    group: List<Int>,
    into: Int,
): HttpResponse =
    post("/api/tasks/$id/speakers/merge") {
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                putJsonArray("group") { group.forEach { add(it) } }
                put("into", into)
            }.toString(),
        )
    }

/** The body of `GET /api/tasks/{id}/turns`. */
private suspend fun HttpClient.turnsOf(id: Long): JsonObject = Json.parseToJsonElement(get("/api/tasks/$id/turns").bodyAsText()).jsonObject

/** The task's speakers as `/turns` reports them. */
private suspend fun HttpClient.speakersOf(id: Long): JsonElement = turnsOf(id).getValue("speakers")

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
