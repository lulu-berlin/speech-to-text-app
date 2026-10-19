package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeGreaterThan
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
 * Item 44, test-list item "Item 38's long clip plays past the former stall point through the new path":
 * playback serves the stored canonical clip itself, so a clip longer than item 38's ~90 s stall point is
 * complete and seekable end to end — the served file's declared duration equals what it really decodes to and
 * the task's own duration, and a Range at the end is playable audio, not a stall.
 */
class AudioLongPlaybackSpec :
    BehaviorSpec({
        tags(Integration)

        given("a COMPLETED task whose stored clip is 100 s long (queue paused)") {
            `when`("its /audio is requested whole, and its tail as a Range") {
                then("the served file declares and decodes its full 100 s, and the tail bytes are audio") {
                    val runner = ProcessRunner()
                    val dataDir = tempdir("stt-long-playback").toPath()
                    val fixtures = tempdir("stt-long-fixtures").toPath()
                    // 100 s of 440 Hz at 16 kHz mono: past item 38's ~90 s stall point, and 100 000 ms is
                    // 400 whole 250 ms frames, so the canonical split has no partial frame (by hand).
                    val source = fixtures.resolve("source.wav")
                    sineWav(source, seconds = 100.0, sampleRate = 16_000, channels = 1)
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
                                    queuedTask("long.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "long audio".toByteArray().inputStream(),
                                )
                            storeClip(db, id, split.header, FlacRows.from(split).toCanonicalRows())
                            tasks.setDurationMs(id, 100_000)
                            id
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val whole = client.get("/api/tasks/$id/audio")
                        whole.status shouldBe HttpStatusCode.OK
                        whole.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                        whole.bodyAsBytes() shouldBe clipBytes

                        val served = fixtures.resolve("served.flac")
                        Files.write(served, whole.bodyAsBytes())

                        // The player's timeline (the task's durationMs, 100 s) is what the file declares and
                        // what it really decodes to: 100 s × 16 000 samples/s, by hand.
                        flacSamples(served) shouldBe 1_600_000L
                        decodeSamples(runner, fixtures.resolve("served.raw"), served) shouldBe 1_600_000L

                        // A browser's end-seek: the last 8 KiB. The served file's own header plus those bytes
                        // decode to real audio — the former stall point is not in the file at all.
                        val size = clipBytes.size.toLong()
                        val tailStart = size - 8_192L
                        val tail = client.get("/api/tasks/$id/audio") { header(HttpHeaders.Range, "bytes=$tailStart-") }
                        tail.status shouldBe HttpStatusCode.PartialContent
                        tail.headers[HttpHeaders.ContentRange] shouldBe "bytes $tailStart-${size - 1}/$size"
                        tail.bodyAsBytes() shouldBe clipBytes.copyOfRange(tailStart.toInt(), size.toInt())

                        val headerSize = flacAudioStart(served)
                        val probe = fixtures.resolve("tail-probe.flac")
                        Files.write(probe, clipBytes.copyOf(headerSize.toInt()).plus(tail.bodyAsBytes()))
                        decodeSamples(runner, fixtures.resolve("tail.raw"), probe) shouldBeGreaterThan 0L
                    }
                }
            }
        }
    })
