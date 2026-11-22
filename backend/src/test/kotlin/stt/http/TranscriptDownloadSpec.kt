package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
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
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.nio.file.Path

class TranscriptDownloadSpec :
    BehaviorSpec({
        given("a COMPLETED task with segments and stored speakers (queue paused)") {
            `when`("downloading its transcript as txt, srt and vtt") {
                then("each returns the rendered output as an attachment with the right content type") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        completedTask(
                            dataDir,
                            "memo.wav",
                            listOf(
                                Segment(0, 0, 0, 1_500, " Hello there.", speakerId = null),
                                Segment(0, 1, 1_500, 3_250, " again.", speakerId = null),
                                Segment(1, 0, 3_250, 4_000, " Fine.", speakerId = null),
                            ),
                            speakerIds = listOf(0, 0, 1),
                        )

                    // Golden strings derived by hand from item 10's spec (srt/vtt layouts,
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
                            // An ASCII name is the same in both parameters (item 76: filename* beside filename).
                            response.headers[HttpHeaders.ContentDisposition] shouldBe
                                "attachment; filename=\"${expected.third}\"; filename*=UTF-8''${expected.third}"
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
                        completedTask(
                            dataDir,
                            "off.wav",
                            listOf(Segment(0, 0, 0, 1_000, " One.", speakerId = null)),
                            speakersMode = SpeakersMode.OFF,
                        )

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

        given("a COMPLETED task with right-to-left text and an Arabic speaker name (queue paused)") {
            `when`("downloading its transcript as txt, srt and vtt") {
                then("each body is exactly the stored text in UTF-8: no direction marks, isolates or BOM") {
                    // Item 76: direction is presentation only, so the downloads carry the stored text as it
                    // is — no U+200E/U+200F marks, no U+2066–U+2069 isolates, no BOM, nothing reordered.
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        completedTask(
                            dataDir,
                            "rtl.wav",
                            listOf(
                                Segment(0, 0, 0, 2_000, " مرحبا بكم في عام 2024.", speakerId = null),
                                Segment(0, 1, 2_000, 3_500, " WHO: مرحبا", speakerId = null),
                            ),
                            speakerIds = listOf(0, 1),
                            names = mapOf(0 to "أحمد"),
                        )

                    // Golden bytes derived by hand from item 10's layouts: turns أحمد 0–2 000 ms and Speaker 2
                    // 2 000–3 500 ms, each text exactly as stored (trimmed), encoded as UTF-8.
                    val expected =
                        mapOf(
                            "txt" to "[أحمد]\nمرحبا بكم في عام 2024.\n\n[Speaker 2]\nWHO: مرحبا\n",
                            "srt" to
                                "1\n00:00:00,000 --> 00:00:02,000\nأحمد: مرحبا بكم في عام 2024.\n\n" +
                                "2\n00:00:02,000 --> 00:00:03,500\nSpeaker 2: WHO: مرحبا\n\n",
                            "vtt" to
                                "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nأحمد: مرحبا بكم في عام 2024.\n\n" +
                                "00:00:02.000 --> 00:00:03.500\nSpeaker 2: WHO: مرحبا\n\n",
                        )
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        for ((format, text) in expected) {
                            val response = client.get("/api/tasks/$id/transcript?format=$format")
                            response.status shouldBe HttpStatusCode.OK
                            response.readRawBytes().toList() shouldBe text.toByteArray(Charsets.UTF_8).toList()
                        }
                    }
                }
            }
        }

        given("COMPLETED tasks with names a plain filename cannot carry (queue paused)") {
            `when`("downloading their transcripts as txt") {
                then("the attachment names the file exactly through filename* (RFC 6266), beside an ASCII filename") {
                    // Item 76 (discovered): a browser ignores raw UTF-8 in `filename=` and saves the file
                    // under the URL's last segment (`transcript.txt`); RFC 6266's `filename*` carries the
                    // name percent-encoded as UTF-8 (hand-derived from the names' UTF-8 bytes), and the ASCII
                    // `filename` fallback has each non-ASCII character, `"` and `\` as `_`, so a quote in a
                    // name no longer breaks the header.
                    val cases =
                        mapOf(
                            // محاضرة has six letters, الأحد five.
                            "محاضرة الأحد.wav" to
                                "attachment; filename=\"______ _____.txt\"; " +
                                "filename*=UTF-8''%D9%85%D8%AD%D8%A7%D8%B6%D8%B1%D8%A9%20%D8%A7%D9%84%D8%A3%D8%AD%D8%AF.txt",
                            "my \"talk\"\\ 100%; final.wav" to
                                "attachment; filename=\"my _talk__ 100%; final.txt\"; " +
                                "filename*=UTF-8''my%20%22talk%22%5C%20100%25%3B%20final.txt",
                        )
                    for ((filename, disposition) in cases) {
                        val dataDir = tempdir("stt-data").toPath()
                        val id =
                            completedTask(
                                dataDir,
                                filename,
                                listOf(Segment(0, 0, 0, 1_000, " One.", speakerId = null)),
                                speakersMode = SpeakersMode.OFF,
                            )
                        testApplication {
                            application { sttApp(stt.config.testConfig(dataDir)) }
                            val response = client.get("/api/tasks/$id/transcript?format=txt")
                            response.status shouldBe HttpStatusCode.OK
                            response.headers[HttpHeaders.ContentDisposition] shouldBe disposition
                        }
                    }
                }
            }
        }
    })

// A COMPLETED task named [filename] in the database under [dataDir], with the queue paused: its
// [segments], their speakers ([speakerIds], one per segment) when given, and those speakers renamed by
// [names]. Returns the task's id.
private suspend fun completedTask(
    dataDir: Path,
    filename: String,
    segments: List<Segment>,
    speakersMode: SpeakersMode = SpeakersMode.AUTO,
    speakerIds: List<Int>? = null,
    names: Map<Int, String> = emptyMap(),
): Long =
    Database.open(dataDir).use { db ->
        QueueStateRepository(db).setPaused(true)
        val id =
            TaskRepository(db).createFromUpload(
                queuedTask(filename, createdAt = 1).copy(
                    queuePosition = 0,
                    status = TaskStatus.COMPLETED,
                    finishedAt = 1_000,
                    speakersMode = speakersMode,
                ),
                "audio bytes".toByteArray().inputStream(),
            )
        SegmentRepository(db).apply {
            insertAll(id, segments)
            if (speakerIds != null) storeSpeakers(id, speakerIds)
            names.forEach { (speakerId, name) -> renameSpeaker(id, speakerId, name) }
        }
        id
    }
