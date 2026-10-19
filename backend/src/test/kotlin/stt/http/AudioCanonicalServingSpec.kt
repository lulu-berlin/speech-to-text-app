package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import stt.Integration
import stt.audio.FlacRows
import stt.audio.FlacSplitter
import stt.audio.encodeCanonical
import stt.audio.sineWav
import stt.audio.toCanonicalRows
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.db.storeClip
import stt.process.ProcessRunner
import stt.queue.TaskStatus
import java.nio.file.Files

/**
 * Item 44, test-list item "Playback serves the stored audio directly": a completed task's canonical clip
 * (the `audio_header` row plus its `audio_second` rows) is served by `/audio` as the encoded FLAC itself —
 * byte for byte, `audio/flac`, its full declared duration, every Range answered from the stored rows — and no
 * file is created anywhere under the data directory: the database holds the only copy (no `tmp/<id>.flac`).
 */
class AudioCanonicalServingSpec :
    BehaviorSpec({
        tags(Integration)

        given("a COMPLETED task whose canonical clip is stored in the database (queue paused)") {
            `when`("its /audio is requested whole and as ranges") {
                then("the stored FLAC itself is served — seekable over its whole range, no cache file") {
                    val runner = ProcessRunner()
                    val dataDir = tempdir("stt-canonical-serve").toPath()
                    // Fixtures live outside the data directory: the assertions below require the data
                    // directory to hold the database's own files and nothing else.
                    val fixtures = tempdir("stt-canonical-fixtures").toPath()
                    // 2.0 s of 440 Hz at 16 kHz mono, encoded exactly as the app encodes a canonical clip:
                    // 32 000 samples = eight 4 000-sample frames = two one-second rows, by hand.
                    val source = fixtures.resolve("source.wav")
                    sineWav(source, seconds = 2.0, sampleRate = 16_000, channels = 1)
                    val canonical = fixtures.resolve("canonical.flac")
                    encodeCanonical(runner, source, canonical)
                    val clipBytes = Files.readAllBytes(canonical)
                    val split = FlacSplitter.split(clipBytes)

                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            val tasks = TaskRepository(db)
                            val id =
                                tasks.createFromUpload(
                                    queuedTask("memo.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "audio bytes".toByteArray().inputStream(),
                                )
                            storeClip(db, id, split.header, FlacRows.from(split).toCanonicalRows())
                            tasks.setDurationMs(id, 2_000)
                            id
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        // Whole file: the stored FLAC byte for byte.
                        val whole = client.get("/api/tasks/$id/audio")
                        whole.status shouldBe HttpStatusCode.OK
                        whole.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                        whole.bodyAsBytes() shouldBe clipBytes

                        // A browser reads the duration from the header: 2.0 s × 16 000 samples/s, by hand.
                        val served = fixtures.resolve("served.flac")
                        Files.write(served, whole.bodyAsBytes())
                        flacSamples(served) shouldBe 32_000L

                        val size = clipBytes.size.toLong()

                        // A mid-file range and the tail a browser's end-seek reads.
                        val mid = client.get("/api/tasks/$id/audio") { header(HttpHeaders.Range, "bytes=100-399") }
                        mid.status shouldBe HttpStatusCode.PartialContent
                        mid.headers[HttpHeaders.ContentRange] shouldBe "bytes 100-399/$size"
                        mid.bodyAsBytes() shouldBe clipBytes.copyOfRange(100, 400)

                        val tailStart = (size - 8_192L).coerceAtLeast(0L)
                        val tail = client.get("/api/tasks/$id/audio") { header(HttpHeaders.Range, "bytes=$tailStart-") }
                        tail.status shouldBe HttpStatusCode.PartialContent
                        tail.headers[HttpHeaders.ContentRange] shouldBe "bytes $tailStart-${size - 1}/$size"
                        tail.bodyAsBytes() shouldBe clipBytes.copyOfRange(tailStart.toInt(), size.toInt())

                        // Nothing was cached anywhere: the data directory holds the database's own files only.
                        Files
                            .walk(dataDir)
                            .use { paths ->
                                paths
                                    .filter { Files.isRegularFile(it) }
                                    .map { it.fileName.toString() }
                                    .sorted()
                                    .toList()
                            }.shouldBe(listOf("stt.db", "stt.db-shm", "stt.db-wal"))
                    }
                }
            }
        }
    })
